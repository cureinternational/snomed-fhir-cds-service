package org.snomed.cdsservice.service.medication.dose;

import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.snomed.cdsservice.model.CDSCard;
import org.snomed.cdsservice.service.ServiceException;
import org.snomed.cdsservice.service.model.ManyToOneMapEntry;
import org.snomed.cdsservice.service.tsclient.ConceptParameters;
import org.snomed.cdsservice.service.tsclient.FHIRTerminologyServerClient;
import org.snomed.cdsservice.service.tsclient.SnomedConceptNormalForm;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClientException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MedicationRouteValidationTest {
    private static final String SYSTEM = SnomedMedicationDefinedDailyDoseService.SNOMEDCT_SYSTEM;
    private static final Map<String, String> MEDICATIONS = Map.of(
            "69236009", "781766003",
            "27658006", "323510009",
            "108600003", "1145419005",
            "55745002", "374912003",
            "26458009", "392557006");

    private SnomedMedicationDefinedDailyDoseService service;
    private FHIRTerminologyServerClient terminology;

    @BeforeEach
    void setUp() throws ServiceException {
        service = new SnomedMedicationDefinedDailyDoseService();
        terminology = mock(FHIRTerminologyServerClient.class);
        MedicationDoseFormsLoaderService forms = mock(MedicationDoseFormsLoaderService.class);
        ReflectionTestUtils.setField(service, "tsClient", terminology);
        ReflectionTestUtils.setField(service, "doseFormsLoaderService", forms);
        ReflectionTestUtils.setField(service, "routeValidationTsvPath", "CDS_Medication-Route_Validation.tsv");
        ReflectionTestUtils.setField(service, "tsvPath", "nonexistent-ddd-file");
        when(forms.loadDoseFormMap()).thenReturn(List.of(
                new ManyToOneMapEntry(Set.of("421026006"), "O", 3, "oral"),
                new ManyToOneMapEntry(Set.of("385219001"), "P", 3, "parenteral")));
        MEDICATIONS.forEach((product, clinicalDrug) -> when(terminology.expandValueSet(
                "http://snomed.info/sct?fhir_vs=isa/" + product)).thenReturn(List.of(
                new Coding(SYSTEM, product, product), new Coding(SYSTEM, clinicalDrug, clinicalDrug))));
        service.init();
    }

    @Test
    void validatesAllFiveDrugDescendantsWithoutDoseFrequencyOrDdd() {
        for (String code : MEDICATIONS.values()) {
            mockDoseForm(code, "421026006");
            assertTrue(service.checkMedications(List.of(medication(code, "O"))).isEmpty());
            List<CDSCard> cards = service.checkMedications(List.of(medication(code, "P")));
            assertEquals(1, cards.size());
            assertEquals("Validation Error", cards.get(0).getAlertType());
            assertTrue(cards.get(0).getDetail().contains("O (oral)"));
        }
    }

    @Test
    void validatesAnInjectionByItsOwnFormNotAnAssumedOralRoute() {
        mockDoseForm("781766003", "385219001");
        assertTrue(service.checkMedications(List.of(medication("781766003", "P"))).isEmpty());
        assertTrue(service.checkMedications(List.of(medication("781766003", "O")))
                .get(0).getDetail().contains("P (parenteral)"));
    }

    @Test
    void ignoresUnconfiguredMedicationsBeforeAnyLookupOrDosageParsing() {
        assertTrue(service.checkMedications(List.of(medication("96119002", null))).isEmpty());
        verify(terminology, never()).lookup(anyString(), anyString());
    }

    @Test
    void reportsMissingRoutesAndDoesNotInspectDoseUnits() {
        mockDoseForm("323510009", "421026006");
        MedicationRequest request = medication("323510009", null);
        request.getDosageInstructionFirstRep().addDoseAndRate().getDoseQuantity().setUnit("NA");
        List<CDSCard> cards = service.checkMedications(List.of(request));
        assertEquals(1, cards.size());
        assertTrue(cards.get(0).getDetail().contains("O (oral)"));
        assertFalse(cards.get(0).getDetail().contains("dose unit"));
    }

    @Test
    void reportsGenericCodesAndUnsupportedFormsAsUnableToAssess() {
        ConceptParameters generic = mock(ConceptParameters.class);
        when(generic.getPropertyValue("normalFormTerse")).thenReturn("===763158003");
        when(terminology.lookup(SYSTEM, "69236009")).thenReturn(generic);
        assertTrue(service.checkMedications(List.of(medication("69236009", "O")))
                .get(0).getSummary().contains("Unable to validate"));
        verify(generic, never()).getNormalForm();

        mockDoseForm("781766003", "999999999");
        CDSCard card = service.checkMedications(List.of(medication("781766003", "O"))).get(0);
        assertTrue(card.getDetail().contains("no supported manufactured dose form"));
        assertEquals("Validation Error", card.getAlertType());
    }

    @Test
    void retainsOtherRouteResultsWhenTerminologyIsUnavailable() {
        when(terminology.lookup(SYSTEM, "781766003")).thenThrow(new RestClientException("unavailable"));
        mockDoseForm("323510009", "421026006");
        List<CDSCard> cards = service.checkMedications(List.of(
                medication("781766003", "P"), medication("323510009", "P")));
        assertEquals(2, cards.size());
        assertTrue(cards.get(0).getDetail().contains("route has not been validated"));
        assertTrue(cards.stream().allMatch(card -> "Validation Error".equals(card.getAlertType())));
    }

    @Test
    void rejectsMalformedOrUnresolvableScopeInsteadOfSilentlyDisablingValidation(@TempDir Path temp) throws Exception {
        Path invalid = temp.resolve("route-scope.tsv");
        Files.writeString(invalid, "Medication\tMedication SNOMED Code\nAmiodarone\t\n");
        ReflectionTestUtils.setField(service, "routeValidationTsvPath", invalid.toString());
        assertThrows(ServiceException.class, service::init);
        Files.writeString(invalid, "Medication\tMedication SNOMED Code\nUnknown\t123456789\n");
        assertThrows(ServiceException.class, service::init);
    }

    private void mockDoseForm(String code, String doseForm) {
        SnomedConceptNormalForm normalForm = new SnomedConceptNormalForm();
        normalForm.getAttributes().put(SnomedMedicationDefinedDailyDoseService.ATTRIBUTE_HAS_MANUFACTURED_DOSE_FORM, doseForm);
        ConceptParameters parameters = mock(ConceptParameters.class);
        when(parameters.getPropertyValue("normalFormTerse")).thenReturn("===763158003:411116001=" + doseForm);
        when(parameters.getNormalForm()).thenReturn(normalForm);
        when(terminology.lookup(SYSTEM, code)).thenReturn(parameters);
    }

    private MedicationRequest medication(String code, String route) {
        MedicationRequest request = new MedicationRequest();
        request.getMedicationCodeableConcept().addCoding(new Coding(SYSTEM, code, code));
        if (route != null) {
            request.addDosageInstruction().getRoute().setText(route);
        }
        return request;
    }
}
