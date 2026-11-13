# SNOMED-CT FHIR CDS Service Demonstrator

This is a Clinical Decision Support service for demonstration. CDS rules are driven by SNOMED CT value sets
and the [HL7 CDS-Hooks v2.0](https://cds-hooks.hl7.org/2.0/) standard.

## Introduction to CDS Alerts for Medication Contraindications
Clinical decision support (CDS) alerts for medication contraindications can be essential tools for improving
patient safety and medication management in healthcare settings. Medication contraindications are situations 
in which the use of a particular drug is not advisable due to a patient's medical history, current conditions,
or concurrent medication use. When medication contraindications are overlooked, patients may experience adverse
drug reactions, worsening of their medical conditions, or other harmful outcomes.

CDS alerts can help clinicians avoid prescribing medications that are contraindicated for their patients by
providing real-time notifications and warnings during the medication ordering process. These alerts can help clinicians
make more informed and safer medication decisions. By using these alerts, healthcare organizations can reduce the risk of 
medication errors, adverse drug events, and other harmful outcomes, ultimately improving patient safety 
and quality of care.

## This Demonstration Service
This demonstration service can create alerts for medication contraindications based on a patient's existing conditions.
The business rules are maintained in a spreadsheet to enable easy access and maintenance. 

The example rules are defined using SNOMED CT concepts at the highest relevant level using medicinal products and findings.
The SNOMED CT hierarchy is used so that rules are triggered when subtype medications are being ordered or subtype 
findings are in the patient record.

This service is fast. Rules and SNOMED CT subtypes are cached during startup allowing typical API response times 
of a tenth of a second.

## CDS Services API
### Service Discovery
This service implements the [discovery endpoint](https://cds-hooks.hl7.org/2.0/#discovery) that describes the available CDS Services.

### "Medication Order Select" Service
This service can be called when creating a medication order. The service will return user alerts if there are any know contraindications 
between the drug being ordered and the existing conditions on the patient record.
#### Spreadsheet Driven Rules
The business logic for the medication order select CDS service are driven by rules authored in a spreadsheet, see 'CDS_Medication-Condition_Cards.xlsx'. 

This spreadsheet uses a simple template format for the user messages where: 
- `{{RuleCondition}}` is the condition label from the rule sheet
- `{{RuleMedication}}` is the medication label from the rule sheet
- `{{ActualCondition}}` is the condition from the patient record that triggered the rule
- `{{ActualMedication}}` is the medication from the medication order that triggered the rule

### "Hello Test" Service
There is a simple hello-test service that takes no inputs, does not require a terminology server, and responds with a simple CDS Card with a greeting. 
This is intended to provide an early test point while integrating the service. 

## Hive 137406: pair-specific drug-drug interaction cards

### Why the response changes

Previously, medication reference enrichment selected the first medication request
matching a SNOMED code. Other formulations sharing that code were missing from
the alert. Returning every matching formulation fixes that omission, but one
shared card still disappears from all medication rows when Bahmni removes any
referenced drug.

Drug-drug interactions now return one card per distinct interacting formulation
pair. The rule's two SNOMED value sets determine the sides; names and identical
SNOMED codes are not used to infer which formulations are alternatives.

For Amitriptyline A and five Amoxicillin-containing formulations B1 through B5:

```text
Before: one card referencing A, B1, B2, B3, B4, B5
After:  five cards: A+B1, A+B2, A+B3, A+B4, A+B5
```

Each card has exactly the two paired medication references, including their
original SNOMED and local codings. Its summary is generated for that pair rather
than copied from the full prescription list.

### API and identity

- The endpoint, request payload and response field names are unchanged.
- A rule with M matching formulations on one side and N on the other can return
  M x N cards. The implementation does not cap or silently discard valid pairs.
- A formulation is not paired with itself. Reversed duplicate pairs within the
  same rule are emitted once if the rule's value sets overlap.
- Each card UUID is deterministic, derived from the rule UUID and the unordered
  pair of formulation identities. Different rules remain distinct.
- The OpenMRS drug UUID in the `https://fhir.openmrs.org` coding is the preferred
  formulation identity. Order UUIDs, dose, display text and request ordering do
  not change that identity. Repeated orders for the same formulation share a card.
- Without an OpenMRS coding, identity uses the sorted, distinct system/code
  mappings. Clients must supply different coded identities to distinguish
  formulations sharing a SNOMED code; different display names alone do not
  distinguish them. Changing those fallback mappings changes the card UUID.
- Drug-condition, drug-allergy and dosage alert generation are not split into
  pairs by this change.

**Compatibility impact:** drug-drug card UUIDs no longer equal the spreadsheet's
rule UUID, even when only one pair matches. Clients must treat the returned UUID
as the alert identity rather than a rule identifier. Existing rule-level
dismissals do not migrate to the new pair UUIDs, so these interactions can appear
active again on the first evaluation after rollout.

### Frontend behavior and accepted limitations

This is a backend-only change. It does not change Bahmni's removal/undo flow or
require frontend PR #303.

| Action | Behavior with pair-specific cards |
|---|---|
| Remove B1 | Only A+B1 is removed; A+B2 through A+B5 remain. |
| Remove the only A formulation | All five cards are removed. |
| Remove every B formulation | No A+B cards remain. |
| Dismiss A+B1 | Other pairs remain independently active. |
| Repeat the same evaluation | The same rule/formulation pair retains its UUID. |

More cards means more warnings on the common medication's row. Critical pairs
can require separate dismissal reasons and audit submissions. This is an
intentional change from one acknowledgement per interaction rule.

The existing local undo cache does not validate whether both drugs in a cached
pair remain prescribed. For example, stop A, stop B1, then undo A only: Bahmni can
restore the cached A+B1 card even though B1 remains stopped. This backend change
does not fix that limitation because this undo path makes no CDSS request.
Likewise, removing one of multiple orders for the same drug UUID can remove a
formulation-level card while another order remains. These cases need an
order-aware or current-prescription-aware frontend follow-up.

No new network calls, dosage recalculation, dismissal aggregation or cache
invalidation are introduced. A fresh backend evaluation returns only the pairs
represented by the medication requests it receives; it cannot correct an
inaccurate client medication list.

### Verification and rollout

Run the medication service regression tests with Java 17:

```sh
mvn -Dtest=MedicationOrderSelectCDSServiceTest test
```

Before rollout, verify shared-SNOMED formulations, different descendant codes on
the same rule side, multiple formulations on both sides, stable UUIDs after
save/reordering, pair-specific summaries, duplicate orders and overlapping rule
sets. In the unchanged frontend, verify removal of one B, the last B and the only
A, plus independent dismissal. Review the additional critical-alert workload and
the undo limitations above as release acceptance items, not as resolved defects.

## Running the Service
### Prerequisites
- Java 17
- Access to a FHIR Terminology Server capable of expanding [SNOMED CT Implicit Value Sets using ECL](https://www.hl7.org/fhir/snomedct.html#implicit).

### Starting the Service
Download the latest jar file from the releases page.  
_(Alternatively the jar file can be compiled from the source code using
[Apache Maven](https://maven.apache.org/guides/getting-started/maven-in-five-minutes.html) `mvn install`)._

Start the service on the command line:
```
java -Xms2g -jar snomed-fhir-cds-service.jar \
  --fhir.terminology-server.url=FHIR_TS
```
Where:
- `FHIR_TR` is a Terminology Server FHIR API URL.

### Testing the Service
Download and import the [Postman Collection](Postman_collection.json) for API examples.
