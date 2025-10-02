/*
 * Copyright © 2015 Integrated Knowledge Management (support@ikm.dev)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.ikm.komet.kview.mvvm.viewmodel;

import static dev.ikm.komet.kview.mvvm.viewmodel.stamp.StampFormViewModelBase.Properties.IS_CONFIRMED_OR_SUBMITTED;
import static dev.ikm.komet.kview.mvvm.viewmodel.stamp.StampFormViewModelBase.Properties.STATUS;
import static dev.ikm.tinkar.coordinate.stamp.StampFields.MODULE;
import static dev.ikm.tinkar.coordinate.stamp.StampFields.PATH;
import dev.ikm.komet.framework.builder.AxiomBuilderRecord;
import dev.ikm.komet.framework.builder.ConceptEntityBuilder;
import dev.ikm.komet.framework.builder.DescriptionBuilderRecord;
import dev.ikm.komet.framework.view.ViewProperties;
import dev.ikm.komet.kview.controls.Toast;
import dev.ikm.komet.kview.mvvm.model.DescrName;
import dev.ikm.komet.kview.mvvm.view.journal.JournalController;
import dev.ikm.komet.kview.mvvm.viewmodel.stamp.StampFormViewModelBase;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.NonExistentValue;
import dev.ikm.tinkar.common.service.TinkExecutor;
import dev.ikm.tinkar.component.Stamp;
import dev.ikm.tinkar.coordinate.edit.EditCoordinateRecord;
import dev.ikm.tinkar.entity.ConceptEntity;
import dev.ikm.tinkar.entity.ConceptRecord;
import dev.ikm.tinkar.entity.Entity;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.RecordListBuilder;
import dev.ikm.tinkar.entity.SemanticRecord;
import dev.ikm.tinkar.entity.SemanticRecordBuilder;
import dev.ikm.tinkar.entity.SemanticVersionRecordBuilder;
import dev.ikm.tinkar.entity.StampEntity;
import dev.ikm.tinkar.entity.StampEntityVersion;
import dev.ikm.tinkar.entity.graph.DiTreeEntity;
import dev.ikm.tinkar.entity.graph.EntityVertex;
import dev.ikm.tinkar.entity.transaction.CommitTransactionTask;
import dev.ikm.tinkar.entity.transaction.Transaction;
import dev.ikm.tinkar.terms.ConceptFacade;
import dev.ikm.tinkar.terms.EntityFacade;
import dev.ikm.tinkar.terms.EntityProxy;
import dev.ikm.tinkar.terms.State;
import dev.ikm.tinkar.terms.TinkarTerm;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.collections.ObservableList;
import org.carlfx.cognitive.validator.MessageType;
import org.carlfx.cognitive.validator.ValidationMessage;
import org.carlfx.cognitive.viewmodel.ViewModel;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.list.MutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

public class ConceptViewModel extends FormViewModel {
    private static final Logger LOG = LoggerFactory.getLogger(ConceptViewModel.class);

    // --------------------------------------------
    // Known properties
    // --------------------------------------------
    public static String CURRENT_ENTITY = "entityFacade";
    public static String FULLY_QUALIFIED_NAMES = "fullyQualifiedNames";
    public static String CONCEPT_STAMP_VIEW_MODEL = "stampViewModel";
    public static String OTHER_NAMES = "otherNames";

    public static String AXIOM = "axiom";
    // Axiom values
    public static final String SUFFICIENT_SET = "Sufficient Set";
    public static final String NECESSARY_SET = "Necessary Set";


    public ConceptViewModel() {
        super(); // addProperty(MODE, VIEW); By default
        addProperty(CURRENT_ENTITY, (EntityFacade) null)
                .addProperty(FULLY_QUALIFIED_NAMES,  (Collection) new ArrayList<>())
                .addProperty(OTHER_NAMES, (Collection) new ArrayList<>())
                .addProperty(CONCEPT_STAMP_VIEW_MODEL, (ViewModel) null)
                .addProperty(AXIOM, (String) null);

        //FIXME add a STAMP validator

        // In Create Mode the fqn is required.
        addValidator(FULLY_QUALIFIED_NAMES, "Fully Qualified Names",(ObservableList<?> observableList, ViewModel _ ) -> {
            if (observableList.isEmpty()){
                return new ValidationMessage(FULLY_QUALIFIED_NAMES, MessageType.ERROR, "${%s} is required".formatted(FULLY_QUALIFIED_NAMES));
            }
            return VALID;
        });

        // Axiom should be selected
        addValidator(AXIOM, "Axiom",(ReadOnlyStringProperty prop, ViewModel _ ) -> {
            if (prop.isNull().get()
                    || (prop.get() instanceof String axiom
                    && !(SUFFICIENT_SET.equals(axiom) || NECESSARY_SET.equals(axiom)))) {
                return new ValidationMessage(AXIOM, MessageType.ERROR, "${%s} is required and must be a %s or %s. Axiom = %s".formatted(AXIOM, SUFFICIENT_SET, NECESSARY_SET, prop.get()));
            }
            return VALID;
        });
    }

    /**
     * Validates the view model and if there are no errors, save to the database.
     * Is called everytime user is adding data to ConceptViewModel.
     *
     * @return
     */
    public boolean createConcept(StampFormViewModelBase stampFormViewModel) {
        save(); // View Model xfer values. does not save to the database but validates data and then copies data from properties to model values.

        // Validation errors will not create record.
        if (!getValidationMessages().isEmpty()) {
            return false;
        }

        // stamp is populated?
        if (!(Boolean)stampFormViewModel.getPropertyValue(IS_CONFIRMED_OR_SUBMITTED)) {
            return false;
        }

        // Get Stamp Info
        ViewProperties viewProperties = getViewProperties();
        State status = stampFormViewModel.getValue(STATUS);
        EntityFacade author = viewProperties.nodeView().editCoordinate().getAuthorForChanges();
        EntityFacade module = stampFormViewModel.getValue(MODULE);
        EntityFacade path = stampFormViewModel.getValue(PATH);

        // Set up Transaction and Builder
        List<DescrName> fqnList = getObservableList(FULLY_QUALIFIED_NAMES);

        Transaction transaction = Transaction.make("New concept for: " + fqnList.getFirst().getNameText());
        StampEntity stampEntity = transaction.getStamp(status, author.nid(),
                module.nid(), path.nid());

        ConceptEntityBuilder newConceptBuilder = ConceptEntityBuilder.builder(stampEntity);

        // Configure FQN(s)
        fqnList.forEach(fqn -> newConceptBuilder.with(
                new DescriptionBuilderRecord(asConceptFacade(fqn.getLanguage()), fqn.getNameText(), TinkarTerm.FULLY_QUALIFIED_NAME_DESCRIPTION_TYPE, asConceptFacade(fqn.getCaseSignificance()))));

        // Configure Other Name(s)
        List<DescrName> otherNamesList = (List<DescrName>) getValueMap().get(OTHER_NAMES);
        otherNamesList.forEach(otherName -> newConceptBuilder.with(
                new DescriptionBuilderRecord(asConceptFacade(otherName.getLanguage()), otherName.getNameText(), TinkarTerm.REGULAR_NAME_DESCRIPTION_TYPE, asConceptFacade(otherName.getCaseSignificance()))));

        // Configure Stated Axiom
        AxiomBuilderRecord axiomBuilder = newConceptBuilder.axiomBuilder();
        if (NECESSARY_SET.equals(getValue(AXIOM))) {
            axiomBuilder.withNecessarySet(
                    axiomBuilder.makeRoleGroup(
                            axiomBuilder.makeSome(TinkarTerm.PART_OF, TinkarTerm.ANONYMOUS_CONCEPT)));
        } else if (SUFFICIENT_SET.equals(getValue(AXIOM))) {
            axiomBuilder.withSufficientSet(
                    axiomBuilder.makeRoleGroup(
                            axiomBuilder.makeSome(TinkarTerm.PART_OF, TinkarTerm.ANONYMOUS_CONCEPT)));
        }

        ImmutableList<EntityFacade> entitiesBuilt = newConceptBuilder.build();
        ConceptFacade conceptFacade = entitiesBuilt.stream()
                .filter(entityFacade -> entityFacade instanceof ConceptFacade)
                .map(entityFacade -> (ConceptFacade) entityFacade)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No Concepts built by ConceptEntityBuilder"));

        try {
            TinkExecutor.threadPool().submit(new CommitTransactionTask(transaction)).get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }

        String pathText = getViewProperties().calculator().getDescriptionTextOrNid(path.nid());
        String moduleText = getViewProperties().calculator().getDescriptionTextOrNid(module.nid());
        String statusText = getViewProperties().calculator().getDescriptionTextOrNid(status.nid());

        // alert the user of the concept being created and were it exists
        JournalController.toast()
            .show(
                Toast.Status.SUCCESS,
                String.format("Concept created %s, %s, %s", pathText, moduleText, statusText)
            );

        // place inside as current Concept
        setValue(CURRENT_ENTITY, conceptFacade);
        setPropertyValue(CURRENT_ENTITY, conceptFacade);
        setValue(MODE, EDIT);
        setPropertyValue(MODE, EDIT);
        return true;
    }

    private ConceptFacade asConceptFacade(EntityFacade entityFacade) {
        Entity entity = Entity.get(entityFacade).orElseThrow();
        if (entity instanceof ConceptEntity) {
            return ConceptFacade.make(entityFacade.nid());
        } else {
            throw new IllegalArgumentException("Cannot Cast EntityFacade to ConceptFacade: " + entityFacade);
        }
    }

    public void addOtherName(EditCoordinateRecord editCoordinateRecord, DescrName otherName) {

        Transaction transaction = Transaction.make();

        StampEntity stampEntity = transaction.getStamp(
                State.fromConceptNid(otherName.getStatus().nid()), // active, inactive, etc
                System.currentTimeMillis(),
                getViewProperties().nodeView().editCoordinate().getAuthorForChanges().nid(),
                otherName.getModule().nid(), // SNOMED CT, LOINC, etc
                TinkarTerm.DEVELOPMENT_PATH.nid()); // Should this be defaulted???

        // get the public id of the referenced concept
        PublicId conceptRecordPublicId =  otherName.getParentConcept();

        int conceptNid = EntityService.get().nidForPublicId(conceptRecordPublicId);

        // the versions that we will first populate with the existing versions of the semantic
        RecordListBuilder versions = RecordListBuilder.make();

        // the new semantic will need a new public id
        PublicId newOtherNamePublicId = PublicIds.newRandom();

        SemanticRecord descriptionSemantic = SemanticRecord.makeNew(newOtherNamePublicId, TinkarTerm.DESCRIPTION_PATTERN.nid(),
                conceptNid, versions);

        // we are grabbing the form data
        // populating the field values for the new version we are writing
        MutableList<Object> descriptionFields = Lists.mutable.empty();
        descriptionFields.add(otherName.getLanguage());
        descriptionFields.add(otherName.getNameText());
        descriptionFields.add(otherName.getCaseSignificance());
        descriptionFields.add(TinkarTerm.REGULAR_NAME_DESCRIPTION_TYPE);

        // iterating over the existing versions and adding them to a new record list builder
        descriptionSemantic.versions().forEach(version -> versions.add(version));

        // adding the new (edit form) version here
        versions.add(SemanticVersionRecordBuilder.builder()
                .chronology(descriptionSemantic)
                .stampNid(stampEntity.nid())
                .fieldValues(descriptionFields.toImmutable())
                .build());

        // apply the updated versions to the new semantic record
        SemanticRecord newSemanticRecord = SemanticRecordBuilder.builder(descriptionSemantic).versions(versions.toImmutable()).build();

        otherName.setSemanticPublicId(newSemanticRecord.publicId());
        // put the new semantic record in the transaction
        transaction.addComponent(newSemanticRecord);

        // perform the save
        Entity.provider().putEntity(newSemanticRecord);

        // commit the transaction
        CommitTransactionTask commitTransactionTask = new CommitTransactionTask(transaction);
        TinkExecutor.threadPool().submit(commitTransactionTask);

        LOG.info("transaction complete");
    }

    public ViewProperties getViewProperties() {
        return getPropertyValue(VIEW_PROPERTIES);
    }

}