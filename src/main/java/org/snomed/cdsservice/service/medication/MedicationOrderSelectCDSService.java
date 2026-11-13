package org.snomed.cdsservice.service.medication;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import jakarta.annotation.PostConstruct;
import org.hl7.fhir.r4.model.*;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snomed.cdsservice.model.CDSCard;
import org.snomed.cdsservice.model.CDSCoding;
import org.snomed.cdsservice.model.CDSReference;
import org.snomed.cdsservice.model.CDSTrigger;
import org.snomed.cdsservice.rest.pojo.CDSRequest;
import org.snomed.cdsservice.service.*;
import org.snomed.cdsservice.service.medication.dose.SnomedMedicationDefinedDailyDoseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class MedicationOrderSelectCDSService extends CDSService {

	private static final String SKIP_DOSAGE_CHECK_EXTENSION_URL = "https://bahmni.org/fhir/StructureDefinition/skip-dosage-check";

	private final Logger logger = LoggerFactory.getLogger(getClass());

	@Autowired
	private FhirContext fhirContext;

	@Autowired
	private MedicationConditionRuleLoaderService ruleLoaderService;

	@Autowired
	private MedicationCombinationRuleLoaderService medicationRuleLoaderService;

	@Autowired
	private MedicationAllergyRuleLoaderService medicationAllergyRuleLoaderService;

	@Autowired
	private SnomedMedicationDefinedDailyDoseService definedDailyDoseService;

	private List<CDSTrigger> medicationOrderSelectTriggers;

	private List<CDSTrigger> drugDrugInteractionTriggers;

	private List<CDSTrigger> medicationAllergyTriggers;

	public MedicationOrderSelectCDSService() {
		super("medication-order-select");
		setPrefetch(Map.of(
				"conditions", "Condition?patient={{context.patientId}}&category=problem-list-item&status=active",
				"draftMedicationRequests", "MedicationRequest?patient={{context.patientId}}&status=draft",
				"allergies", "AllergyIntolerance?patient={{context.patientId}}"
		));
	}

	@PostConstruct
	public void init() throws ServiceException {
		medicationOrderSelectTriggers = ruleLoaderService.loadTriggers();
		drugDrugInteractionTriggers = medicationRuleLoaderService.loadTriggers();
		medicationAllergyTriggers = medicationAllergyRuleLoaderService.loadTriggers();
	}

	@Override
	public List<CDSCard> call(CDSRequest cdsRequest) {
		Map<String, String> prefetch = cdsRequest.getPrefetchStrings();
		if (prefetch == null || prefetch.get("patient") == null || prefetch.get("conditions") == null || prefetch.get("draftMedicationRequests") == null) {
			throw new ResponseStatusException(412, "Request does not include required prefetch information: patient, diagnosis and medications.", null);
		}

		IParser parser = fhirContext.newJsonParser();
		List<Condition> activeDiagnoses = getPrefetchResourcesFromBundle(prefetch, "conditions", Condition.class, parser);
		List<MedicationRequest> medicationRequests = getPrefetchResourcesFromBundle(prefetch, "draftMedicationRequests", MedicationRequest.class, parser);

		Set<Coding> activeDiagnosesCodings = getCodings(activeDiagnoses.stream().map(Condition::getCode));
		Set<Coding> draftMedicationOrderCodings = getCodings(medicationRequests.stream().map(MedicationRequest::getMedicationCodeableConcept));
		Set<Coding> allergyCodings = getPrefetchAllergyCodings(prefetch, parser);

		List<CDSCard> cards = new ArrayList<>();
		for (CDSTrigger trigger : medicationOrderSelectTriggers) {
			CDSCard card = trigger.createRelevantCard(activeDiagnosesCodings, draftMedicationOrderCodings);
			if (card != null) {
				addCodesFromOtherCodingSystemsForDraftMedications(card.getReferenceMedications(), medicationRequests);
				addCodesFromOtherCodingSystemsForConditions(card.getReferenceConditions(), activeDiagnoses);
				cards.add(card);
			}
		}

		medicationAllergyTriggers.forEach(trigger -> {
			CDSCard card = trigger.createRelevantCard(allergyCodings, draftMedicationOrderCodings);
			if (card != null) {
				addCodesFromOtherCodingSystemsForDraftMedications(card.getReferenceMedications(), medicationRequests);
				cards.add(card);
			}
		});

		drugDrugInteractionTriggers.forEach(trigger -> addDrugInteractionCards(trigger, medicationRequests, cards));

		List<MedicationRequest> dosageCheckMedicationRequests = medicationRequests.stream()
				.filter(medicationRequest -> !shouldSkipDosageCheck(medicationRequest))
				.collect(Collectors.toList());
		cards.addAll(definedDailyDoseService.checkMedications(dosageCheckMedicationRequests));

		return cards;
	}

	private void addDrugInteractionCards(CDSTrigger trigger, List<MedicationRequest> medicationRequests, List<CDSCard> cards) {
		Map<String, MedicationRequest> firstSide = getMatchingMedications(trigger, trigger.getMedicationCodings(), medicationRequests);
		Map<String, MedicationRequest> secondSide = getMatchingMedications(trigger, trigger.getMedication2Codings(), medicationRequests);
		Set<String> pairIds = new HashSet<>();
		for (Map.Entry<String, MedicationRequest> first : firstSide.entrySet()) {
			for (Map.Entry<String, MedicationRequest> second : secondSide.entrySet()) {
				if (first.getKey().equals(second.getKey())) {
					continue;
				}
				String pairKey = trigger.getCard().getUuid() + "\n"
						+ Stream.of(first.getKey(), second.getKey()).sorted().collect(Collectors.joining("\n"));
				String pairId = UUID.nameUUIDFromBytes(pairKey.getBytes(StandardCharsets.UTF_8)).toString();
				if (!pairIds.add(pairId)) {
					continue;
				}
				CDSCard card = trigger.createRelevantCard(
						getCodings(Stream.of(second.getValue().getMedicationCodeableConcept())),
						getCodings(Stream.of(first.getValue().getMedicationCodeableConcept())));
				if (card != null) {
					addCodesFromOtherCodingSystemsForDraftMedications(card.getReferenceMedications(), List.of(first.getValue(), second.getValue()));
					cards.add(new CDSCard(pairId, card.getSummary(), card.getDetail(), card.getIndicator(),
							card.getSource(), card.getReferenceMedications(), card.getReferenceConditions(), card.getAlertType()));
				}
			}
		}
	}

	private Map<String, MedicationRequest> getMatchingMedications(CDSTrigger trigger, Collection<Coding> ruleCodings, List<MedicationRequest> medicationRequests) {
		// ponytail: formulation-level cards; order-level cards need an order-aware client.
		return medicationRequests.stream()
				.filter(request -> !trigger.getIntersection(request.getMedicationCodeableConcept().getCoding(), ruleCodings).isEmpty())
				.collect(Collectors.toMap(this::getMedicationIdentity, Function.identity(), (first, second) -> first, LinkedHashMap::new));
	}

	private String getMedicationIdentity(MedicationRequest request) {
		List<Coding> codings = request.getMedicationCodeableConcept().getCoding();
		String identity = codings.stream()
				.filter(coding -> "https://fhir.openmrs.org".equals(coding.getSystem()) && coding.hasCode())
				.map(coding -> coding.getSystem() + "|" + coding.getCode())
				.sorted()
				.findFirst()
				.orElseGet(() -> codings.stream().filter(Coding::hasCode)
						.map(coding -> coding.getSystem() + "|" + coding.getCode())
						.distinct().sorted().collect(Collectors.joining("\n")));
		return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
	}

	private boolean shouldSkipDosageCheck(MedicationRequest medicationRequest) {
		return medicationRequest.getExtension().stream()
				.anyMatch(extension -> SKIP_DOSAGE_CHECK_EXTENSION_URL.equals(extension.getUrl())
						&& extension.getValue() instanceof BooleanType
						&& ((BooleanType) extension.getValue()).booleanValue());
	}

	private void addCodesFromOtherCodingSystemsForConditions(List<CDSReference> referenceConditions, List<Condition> activeDiagnoses) {
		referenceConditions.forEach(referenceCondition-> {
					CDSCoding cdsCoding = referenceCondition.getCoding().get(0);
					Optional<Condition> optionalCondition = activeDiagnoses.stream().filter(condition -> {
						List<Coding> codingList = condition.getCode().getCoding();
						Optional<Coding> optionalCoding = codingList.stream().filter(coding -> coding.getCode().equals(cdsCoding.getCode()) && coding.getSystem().equals(cdsCoding.getSystem())).findFirst();
						return optionalCoding.isPresent();
					}).findFirst();
					optionalCondition.ifPresent(getCDSReferenceConditionConsumer(referenceCondition));
				}
		);
	}

	private void addCodesFromOtherCodingSystemsForDraftMedications(List<CDSReference> referenceMedications, List<MedicationRequest> draftMedicationOrders) {
		List<CDSReference> references = draftMedicationOrders.stream()
				.filter(medicationRequest -> referenceMedications.stream().anyMatch(referenceMedication -> {
					CDSCoding cdsCoding = referenceMedication.getCoding().get(0);
					return medicationRequest.getMedicationCodeableConcept().getCoding().stream()
							.anyMatch(coding -> Objects.equals(coding.getCode(), cdsCoding.getCode()) && Objects.equals(coding.getSystem(), cdsCoding.getSystem()));
				}))
				.map(medicationRequest -> new CDSReference(medicationRequest.getMedicationCodeableConcept().getCoding().stream()
						.map(getCodingCDSCodingFunction()).collect(Collectors.toList())))
				.collect(Collectors.toList());
		referenceMedications.clear();
		referenceMedications.addAll(references);
	}

	@NotNull
	private Consumer<Condition> getCDSReferenceConditionConsumer(CDSReference reference) {
		return condition -> reference.setCoding(condition.getCode().getCoding().stream().map(getCodingCDSCodingFunction()).collect(Collectors.toList()));
	}

	@NotNull
	private Function<Coding, CDSCoding> getCodingCDSCodingFunction() {
		return coding -> new CDSCoding(coding.getSystem(), coding.getCode(), coding.getDisplay());
	}

	@NotNull
	private static Set<Coding> getCodings(Stream<CodeableConcept> codeableConceptStream) {
		return codeableConceptStream
				.map(CodeableConcept::getCoding)
				.flatMap(Collection::stream)
				.collect(Collectors.toSet());
	}

	private <T extends Resource> List<T> getPrefetchResourcesFromBundle(Map<String, String> prefetch, String name, Class<T> theResourceType, IParser parser) {
		String value = prefetch.get(name);
		Bundle bundle = parser.parseResource(Bundle.class, value);
		List<T> resources = new ArrayList<>();
		for (Bundle.BundleEntryComponent component : bundle.getEntry()) {
			Resource resource = component.getResource();
			if (resource.getResourceType().name().equals(theResourceType.getSimpleName())) {
				resources.add((T) resource);
			}
		}
		return resources;
	}

	private Set<Coding> getPrefetchAllergyCodings(Map<String, String> prefetch, IParser parser) {
		if (prefetch == null || prefetch.get("allergies") == null) {
			return Collections.emptySet();
		}
		try {
			List<AllergyIntolerance> allergies = getPrefetchResourcesFromBundle(prefetch, "allergies", AllergyIntolerance.class, parser);
			return getCodings(allergies.stream().map(AllergyIntolerance::getCode));
		} catch (Exception e) {
			logger.warn("Could not parse allergies prefetch, proceeding without allergy rules.", e);
			return Collections.emptySet();
		}
	}

	public void setMedicationOrderSelectTriggers(List<CDSTrigger> medicationOrderSelectTriggers) {
		this.medicationOrderSelectTriggers = medicationOrderSelectTriggers;
	}

	public void setMedicationAllergyTriggers(List<CDSTrigger> medicationAllergyTriggers) {
		this.medicationAllergyTriggers = medicationAllergyTriggers;
	}
}
