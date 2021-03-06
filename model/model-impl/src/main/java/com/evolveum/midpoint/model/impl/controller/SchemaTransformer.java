/**
 * Copyright (c) 2014-2015 Evolveum
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
package com.evolveum.midpoint.model.impl.controller;

import java.util.*;

import com.evolveum.midpoint.prism.Containerable;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.schema.SearchResultList;
import com.evolveum.midpoint.schema.util.CertCampaignTypeUtil;
import com.evolveum.midpoint.schema.util.ObjectTypeUtil;
import org.apache.commons.lang.Validate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.evolveum.midpoint.common.InternalsConfig;
import com.evolveum.midpoint.common.crypto.CryptoUtil;
import com.evolveum.midpoint.model.api.ModelAuthorizationAction;
import com.evolveum.midpoint.model.impl.util.Utils;
import com.evolveum.midpoint.prism.ConsistencyCheckScope;
import com.evolveum.midpoint.prism.Item;
import com.evolveum.midpoint.prism.ItemDefinition;
import com.evolveum.midpoint.prism.PrismContainer;
import com.evolveum.midpoint.prism.PrismContainerDefinition;
import com.evolveum.midpoint.prism.PrismContainerValue;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.PrismObjectDefinition;
import com.evolveum.midpoint.prism.PrismReferenceValue;
import com.evolveum.midpoint.prism.PrismValue;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.prism.xml.XsdTypeMapper;
import com.evolveum.midpoint.repo.api.RepositoryService;
import com.evolveum.midpoint.schema.GetOperationOptions;
import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.schema.util.MiscSchemaUtil;
import com.evolveum.midpoint.security.api.ObjectSecurityConstraints;
import com.evolveum.midpoint.security.api.SecurityEnforcer;
import com.evolveum.midpoint.security.api.SecurityUtil;
import com.evolveum.midpoint.task.api.Task;
import com.evolveum.midpoint.util.exception.AuthorizationException;
import com.evolveum.midpoint.util.exception.ConfigurationException;
import com.evolveum.midpoint.util.exception.ObjectNotFoundException;
import com.evolveum.midpoint.util.exception.SchemaException;
import com.evolveum.midpoint.util.exception.SecurityViolationException;
import com.evolveum.midpoint.util.exception.SystemException;
import com.evolveum.midpoint.util.logging.Trace;
import com.evolveum.midpoint.util.logging.TraceManager;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AuthorizationDecisionType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AuthorizationPhaseType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.LayerType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectPolicyConfigurationType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectReferenceType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectTemplateItemDefinitionType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectTemplateType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.OperationResultStatusType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.OperationResultType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.PropertyAccessType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.PropertyLimitationsType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ReportType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ResourceType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ShadowType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.SystemConfigurationType;
import com.evolveum.prism.xml.ns._public.types_3.ItemPathType;

import javax.xml.namespace.QName;

/**
 * Transforms the schema and objects by applying security constraints,
 * object template schema refinements, etc.
 * 
 * @author semancik
 */
@Component
public class SchemaTransformer {
	
	private static final Trace LOGGER = TraceManager.getTrace(SchemaTransformer.class);
	
	@Autowired(required = true)
	@Qualifier("cacheRepositoryService")
	private transient RepositoryService cacheRepositoryService;
	
	@Autowired(required = true)
	private SecurityEnforcer securityEnforcer;

	@Autowired
	private PrismContext prismContext;

	// TODO why are the following two methods distinct? Clarify their names.
	public <T extends ObjectType> void applySchemasAndSecurityToObjectTypes(Collection<T> objectTypes, 
			GetOperationOptions options, AuthorizationPhaseType phase, Task task, OperationResult result) 
					throws SecurityViolationException, SchemaException, ConfigurationException, ObjectNotFoundException {
		for (T objectType: objectTypes) {
			applySchemasAndSecurity(objectType.asPrismObject(), options, phase, task, result);
		}
	}
	
	public <T extends ObjectType> void applySchemasAndSecurityToObjects(Collection<PrismObject<T>> objects, 
			GetOperationOptions options, AuthorizationPhaseType phase, Task task, OperationResult result) 
					throws SecurityViolationException, SchemaException {
		for (PrismObject<T> object: objects) {
			applySchemaAndSecurityToObject(object, options, phase, task);
		}
	}

	// Expecting that C is a direct child of T.
	// Expecting that container values point to their respective parents (in order to evaluate the security!)
	public <C extends Containerable, T extends ObjectType>
	SearchResultList<C> applySchemasAndSecurityToContainers(SearchResultList<C> originalResultList, Class<T> parentObjectType, QName childItemName,
															GetOperationOptions options, AuthorizationPhaseType phase, Task task, OperationResult result)
			throws SecurityViolationException, SchemaException, ObjectNotFoundException, ConfigurationException {

		List<C> newValues = new ArrayList<>();
		Map<PrismObject<T>,Object> processedParents = new IdentityHashMap<>();
		final ItemPath childItemPath = new ItemPath(childItemName);

		for (C value: originalResultList) {
			Long originalId = value.asPrismContainerValue().getId();
			if (originalId == null) {
				throw new SchemaException("No ID in container value " + value);
			}
			PrismObject<T> parent = ObjectTypeUtil.getParentObject(value);
			boolean wasProcessed;
			if (parent != null) {
				wasProcessed = processedParents.containsKey(parent);
			} else {
				// temporary solution TODO reconsider
				parent = prismContext.createObject(parentObjectType);
				PrismContainer<C> childContainer = parent.findOrCreateItem(childItemPath, PrismContainer.class);
				childContainer.add(value.asPrismContainerValue());
				wasProcessed = false;
			}
			if (!wasProcessed) {
				applySchemasAndSecurity(parent, options, phase, task, result);
				processedParents.put(parent, null);
			}
			PrismContainer<C> updatedChildContainer = parent.findContainer(childItemPath);
			if (updatedChildContainer != null) {
				PrismContainerValue<C> updatedChildValue = updatedChildContainer.getValue(originalId);
				if (updatedChildValue != null) {
					newValues.add(updatedChildValue.asContainerable());
				}
			}
		}
		return new SearchResultList<>(newValues, originalResultList.getMetadata());
	}

	protected <T extends ObjectType> void applySchemaAndSecurityToObject(PrismObject<T> object, GetOperationOptions options, AuthorizationPhaseType phase, Task task) {
		OperationResult subresult = new OperationResult(ModelController.class.getName()+".applySchemasAndSecurityToObjects");
		try {
            applySchemasAndSecurity(object, options, phase, task, subresult);
        } catch (IllegalArgumentException|IllegalStateException|SchemaException |SecurityViolationException |ConfigurationException |ObjectNotFoundException e) {
            LOGGER.error("Error post-processing object {}: {}", new Object[]{object, e.getMessage(), e});
            OperationResultType fetchResult = object.asObjectable().getFetchResult();
            if (fetchResult == null) {
                fetchResult = subresult.createOperationResultType();
                object.asObjectable().setFetchResult(fetchResult);
            } else {
                fetchResult.getPartialResults().add(subresult.createOperationResultType());
            }
            fetchResult.setStatus(OperationResultStatusType.FATAL_ERROR);
        }
	}

	/**
	 * Validate the objects, apply security to the object definition, remove any non-visible properties (security),
	 * apply object template definitions and so on. This method is called for
	 * any object that is returned from the Model Service.  
	 */
	public <O extends ObjectType> void applySchemasAndSecurity(PrismObject<O> object, GetOperationOptions rootOptions,
			AuthorizationPhaseType phase, Task task, OperationResult parentResult) 
					throws SchemaException, SecurityViolationException, ConfigurationException, ObjectNotFoundException {
    	OperationResult result = parentResult.createMinorSubresult(ModelController.class.getName()+".applySchemasAndSecurity");
    	validateObject(object, rootOptions, result);
    	
    	PrismObjectDefinition<O> objectDefinition = object.deepCloneDefinition(true);
    	
    	ObjectSecurityConstraints securityConstraints;
    	try {
	    	securityConstraints = securityEnforcer.compileSecurityConstraints(object, null);
			if (LOGGER.isTraceEnabled()) {
				LOGGER.trace("Security constrains for {}:\n{}", object, securityConstraints==null?"null":securityConstraints.debugDump());
			}
			if (securityConstraints == null) {
				SecurityUtil.logSecurityDeny(object, "because no security constraints are defined (default deny)");
				throw new AuthorizationException("Access denied");
			}
    	} catch (SecurityViolationException | SchemaException | RuntimeException e) {
			result.recordFatalError(e);
			throw e;
		}

    	if (phase == null) {
    		applySchemasAndSecurityPhase(object, securityConstraints, objectDefinition, rootOptions, AuthorizationPhaseType.REQUEST, task, result);
    		applySchemasAndSecurityPhase(object, securityConstraints, objectDefinition, rootOptions, AuthorizationPhaseType.EXECUTION, task, result);
    	} else {
    		applySchemasAndSecurityPhase(object, securityConstraints, objectDefinition, rootOptions, phase, task, result);
    	}
		
		ObjectTemplateType objectTemplateType;
		try {
			objectTemplateType = determineObjectTemplate(object, AuthorizationPhaseType.REQUEST, result);
		} catch (ConfigurationException | ObjectNotFoundException e) {
			result.recordFatalError(e);
			throw e;
		}
		applyObjectTemplateToObject(object, objectTemplateType, result);
		
		result.computeStatus();
		result.recordSuccessIfUnknown();
    }
	
	private <O extends ObjectType> void applySchemasAndSecurityPhase(PrismObject<O> object, ObjectSecurityConstraints securityConstraints, PrismObjectDefinition<O> objectDefinition, 
			GetOperationOptions rootOptions, AuthorizationPhaseType phase, Task task, OperationResult result) 
					throws SchemaException, SecurityViolationException, ConfigurationException, ObjectNotFoundException {
		Validate.notNull(phase);
		try {
			AuthorizationDecisionType globalReadDecision = securityConstraints.getActionDecision(ModelAuthorizationAction.READ.getUrl(), phase);
			if (globalReadDecision == AuthorizationDecisionType.DENY) {
				// shortcut
				SecurityUtil.logSecurityDeny(object, "because the authorization denies access");
				throw new AuthorizationException("Access denied");
			}
			
			AuthorizationDecisionType globalAddDecision = securityConstraints.getActionDecision(ModelAuthorizationAction.ADD.getUrl(), phase);
			AuthorizationDecisionType globalModifyDecision = securityConstraints.getActionDecision(ModelAuthorizationAction.MODIFY.getUrl(), phase);
			applySecurityConstraints((List)object.getValue().getItems(), securityConstraints, globalReadDecision,
					globalAddDecision, globalModifyDecision, phase);
			if (object.isEmpty()) {
				// let's make it explicit
				SecurityUtil.logSecurityDeny(object, "because the subject has not access to any item");
				throw new AuthorizationException("Access denied");
			}
			
			applySecurityConstraintsItemDef(objectDefinition, ItemPath.EMPTY_PATH, securityConstraints, globalReadDecision, globalAddDecision, globalModifyDecision, phase);
			
		} catch (SecurityViolationException | RuntimeException e) {
			result.recordFatalError(e);
			throw e;
		}
	}
	
	public void applySecurityConstraints(List<Item<?,?>> items, ObjectSecurityConstraints securityConstraints, 
			AuthorizationDecisionType defaultReadDecision, AuthorizationDecisionType defaultAddDecision, AuthorizationDecisionType defaultModifyDecision, 
			AuthorizationPhaseType phase) {
		LOGGER.trace("applySecurityConstraints(items): items={}, phase={}, defaults R={}, A={}, M={}",
				new Object[]{items, phase, defaultReadDecision, defaultAddDecision, defaultModifyDecision});
		if (items == null) {
			return;
		}
		Iterator<Item<?,?>> iterator = items.iterator();
		while (iterator.hasNext()) {
			Item<?,?> item = iterator.next();
			ItemPath itemPath = item.getPath();
			AuthorizationDecisionType itemReadDecision = computeItemDecision(securityConstraints, itemPath, ModelAuthorizationAction.READ.getUrl(), defaultReadDecision, phase);
			AuthorizationDecisionType itemAddDecision = computeItemDecision(securityConstraints, itemPath, ModelAuthorizationAction.ADD.getUrl(), defaultReadDecision, phase);
			AuthorizationDecisionType itemModifyDecision = computeItemDecision(securityConstraints, itemPath, ModelAuthorizationAction.MODIFY.getUrl(), defaultReadDecision, phase);
			LOGGER.trace("applySecurityConstraints(item): {}: decisions R={}, A={}, M={}",
					new Object[]{itemPath, itemReadDecision, itemAddDecision, itemModifyDecision});
			ItemDefinition<?> itemDef = item.getDefinition();
			if (itemDef != null) {
				if (itemReadDecision != AuthorizationDecisionType.ALLOW) {
					itemDef.setCanRead(false);
				}
				if (itemAddDecision != AuthorizationDecisionType.ALLOW) {
					itemDef.setCanAdd(false);
				}
				if (itemModifyDecision != AuthorizationDecisionType.ALLOW) {
					itemDef.setCanModify(false);
				}
			}
			if (item instanceof PrismContainer<?>) {
				if (itemReadDecision == AuthorizationDecisionType.DENY) {
					// Explicitly denied access to the entire container
					iterator.remove();
				} else {
					// No explicit decision (even ALLOW is not final here as something may be denied deeper inside)
					AuthorizationDecisionType subDefaultReadDecision = defaultReadDecision;
					if (itemReadDecision == AuthorizationDecisionType.ALLOW) {
						// This means allow to all subitems unless otherwise denied.
						subDefaultReadDecision = AuthorizationDecisionType.ALLOW;
					}
					List<? extends PrismContainerValue<?>> values = ((PrismContainer<?>)item).getValues();
					Iterator<? extends PrismContainerValue<?>> vi = values.iterator();
					while (vi.hasNext()) {
						PrismContainerValue<?> cval = vi.next();
						List<Item<?,?>> subitems = cval.getItems();
						if (subitems != null) {
							applySecurityConstraints(subitems, securityConstraints, subDefaultReadDecision, itemAddDecision, itemModifyDecision, phase);
							if (subitems.isEmpty()) {
								vi.remove();
							}
						}
					}
					if (item.isEmpty()) {
						iterator.remove();
					}
				}
			} else {
				if (itemReadDecision == AuthorizationDecisionType.DENY || (itemReadDecision == null && defaultReadDecision == null)) {
					iterator.remove();
				}
			}
		}
	}
	
	public <D extends ItemDefinition> void applySecurityConstraints(D itemDefinition, ObjectSecurityConstraints securityConstraints,
            AuthorizationPhaseType phase) {
		if (phase == null) {
			applySecurityConstraintsPhase(itemDefinition, securityConstraints, AuthorizationPhaseType.REQUEST);
			applySecurityConstraintsPhase(itemDefinition, securityConstraints, AuthorizationPhaseType.EXECUTION);
		} else {
			applySecurityConstraintsPhase(itemDefinition, securityConstraints, phase);
		}
	}
	
	private <D extends ItemDefinition> void applySecurityConstraintsPhase(D itemDefinition, ObjectSecurityConstraints securityConstraints,
            AuthorizationPhaseType phase) {
		Validate.notNull(phase);
		AuthorizationDecisionType defaultReadDecision = securityConstraints.getActionDecision(ModelAuthorizationAction.READ.getUrl(), phase);
		AuthorizationDecisionType defaultAddDecision = securityConstraints.getActionDecision(ModelAuthorizationAction.ADD.getUrl(), phase);
		AuthorizationDecisionType defaultModifyDecision = securityConstraints.getActionDecision(ModelAuthorizationAction.MODIFY.getUrl(), phase);
		LOGGER.trace("applySecurityConstraints(itemDefs): def={}, phase={}, defaults R={}, A={}, M={}",
				new Object[]{itemDefinition, phase, defaultReadDecision, defaultAddDecision, defaultModifyDecision});
		applySecurityConstraintsItemDef(itemDefinition, ItemPath.EMPTY_PATH, securityConstraints,
				defaultReadDecision, defaultAddDecision, defaultModifyDecision, phase);
				
	}
	
	private <D extends ItemDefinition> void applySecurityConstraintsItemDef(D itemDefinition, ItemPath itemPath, ObjectSecurityConstraints securityConstraints,
			AuthorizationDecisionType defaultReadDecision, AuthorizationDecisionType defaultAddDecision, AuthorizationDecisionType defaultModifyDecision,
            AuthorizationPhaseType phase) {
		AuthorizationDecisionType readDecision = computeItemDecision(securityConstraints, itemPath, ModelAuthorizationAction.READ.getUrl(), defaultReadDecision, phase);
		AuthorizationDecisionType addDecision = computeItemDecision(securityConstraints, itemPath, ModelAuthorizationAction.ADD.getUrl(), defaultAddDecision, phase);
		AuthorizationDecisionType modifyDecision = computeItemDecision(securityConstraints, itemPath, ModelAuthorizationAction.MODIFY.getUrl(), defaultModifyDecision, phase);
		LOGGER.trace("applySecurityConstraints(itemDef): {}: decisions R={}, A={}, M={}",
				new Object[]{itemPath, readDecision, addDecision, modifyDecision});
		if (readDecision != AuthorizationDecisionType.ALLOW) {
			itemDefinition.setCanRead(false);
		}
		if (addDecision != AuthorizationDecisionType.ALLOW) {
			itemDefinition.setCanAdd(false);
		}
		if (modifyDecision != AuthorizationDecisionType.ALLOW) {
			itemDefinition.setCanModify(false);
		}
		
		if (itemDefinition instanceof PrismContainerDefinition<?>) {
			PrismContainerDefinition<?> containerDefinition = (PrismContainerDefinition<?>)itemDefinition;
			List<? extends ItemDefinition> subDefinitions = ((PrismContainerDefinition<?>)containerDefinition).getDefinitions();
			for (ItemDefinition subDef: subDefinitions) {
				if (!subDef.getName().equals(ShadowType.F_ATTRIBUTES)) { // Shadow attributes have special handling
					applySecurityConstraintsItemDef(subDef, new ItemPath(itemPath, subDef.getName()), securityConstraints,
					    readDecision, addDecision, modifyDecision, phase);
				}
			}
		}
	}
		
    public AuthorizationDecisionType computeItemDecision(ObjectSecurityConstraints securityConstraints, ItemPath itemPath, String actionUrl,
			AuthorizationDecisionType defaultDecision, AuthorizationPhaseType phase) {
    	AuthorizationDecisionType explicitDecision = securityConstraints.findItemDecision(itemPath, actionUrl, phase);
//    	LOGGER.trace("Explicit decision for {}: {}", itemPath, explicitDecision);
    	if (explicitDecision != null) {
    		return explicitDecision;
    	} else {
    		return defaultDecision;
    	}
	}
    
    public <O extends ObjectType> ObjectTemplateType determineObjectTemplate(PrismObject<O> object, AuthorizationPhaseType phase, OperationResult result) throws SchemaException, ConfigurationException, ObjectNotFoundException {
    	PrismObject<SystemConfigurationType> systemConfiguration = Utils.getSystemConfiguration(cacheRepositoryService, result);
    	if (systemConfiguration == null) {
    		return null;
    	}
    	ObjectPolicyConfigurationType objectPolicyConfiguration = ModelUtils.determineObjectPolicyConfiguration(object, systemConfiguration.asObjectable());
    	if (objectPolicyConfiguration == null) {
    		return null;
    	}
    	ObjectReferenceType objectTemplateRef = objectPolicyConfiguration.getObjectTemplateRef();
    	if (objectTemplateRef == null) {
    		return null;
    	}
    	PrismObject<ObjectTemplateType> template = cacheRepositoryService.getObject(ObjectTemplateType.class, objectTemplateRef.getOid(), null, result);
    	return template.asObjectable();
    }
    
    public <O extends ObjectType> ObjectTemplateType determineObjectTemplate(Class<O> objectClass, AuthorizationPhaseType phase, OperationResult result) throws SchemaException, ConfigurationException, ObjectNotFoundException {
    	PrismObject<SystemConfigurationType> systemConfiguration = Utils.getSystemConfiguration(cacheRepositoryService, result);
    	if (systemConfiguration == null) {
    		return null;
    	}
    	ObjectPolicyConfigurationType objectPolicyConfiguration = ModelUtils.determineObjectPolicyConfiguration(objectClass, null, systemConfiguration.asObjectable());
    	if (objectPolicyConfiguration == null) {
    		return null;
    	}
    	ObjectReferenceType objectTemplateRef = objectPolicyConfiguration.getObjectTemplateRef();
    	if (objectTemplateRef == null) {
    		return null;
    	}
    	PrismObject<ObjectTemplateType> template = cacheRepositoryService.getObject(ObjectTemplateType.class, objectTemplateRef.getOid(), null, result);
    	return template.asObjectable();
    }
    
    public <O extends ObjectType> void applyObjectTemplateToDefinition(PrismObjectDefinition<O> objectDefinition, ObjectTemplateType objectTemplateType, OperationResult result) throws ObjectNotFoundException, SchemaException {
		if (objectTemplateType == null) {
			return;
		}
		for (ObjectReferenceType includeRef: objectTemplateType.getIncludeRef()) {
			PrismObject<ObjectTemplateType> subTemplate = cacheRepositoryService.getObject(ObjectTemplateType.class, includeRef.getOid(), null, result);
			applyObjectTemplateToDefinition(objectDefinition, subTemplate.asObjectable(), result);
		}
		for (ObjectTemplateItemDefinitionType templateItemDefType: objectTemplateType.getItem()) {
                ItemPathType ref = templateItemDefType.getRef();
                if (ref == null) {
				throw new SchemaException("No 'ref' in item definition in "+objectTemplateType);
                }
                ItemPath itemPath = ref.getItemPath();
                ItemDefinition itemDef = objectDefinition.findItemDefinition(itemPath);
                if (itemDef != null) {
                    applyObjectTemplateItem(itemDef, templateItemDefType, "item " + itemPath + " in object type " + objectDefinition.getTypeName() + " as specified in item definition in " + objectTemplateType);
                } else {
                    OperationResult subResult = result.createMinorSubresult(ModelController.class.getName() + ".applyObjectTemplateToDefinition");
                    subResult.recordPartialError("No definition for item " + itemPath + " in object type " + objectDefinition.getTypeName() + " as specified in item definition in " + objectTemplateType);
                    continue;
                }
		}
	}
	
	private <O extends ObjectType> void applyObjectTemplateToObject(PrismObject<O> object, ObjectTemplateType objectTemplateType, OperationResult result) throws ObjectNotFoundException, SchemaException {
		if (objectTemplateType == null) {
			return;
		}
		for (ObjectReferenceType includeRef: objectTemplateType.getIncludeRef()) {
			PrismObject<ObjectTemplateType> subTemplate = cacheRepositoryService.getObject(ObjectTemplateType.class, includeRef.getOid(), null, result);
			applyObjectTemplateToObject(object, subTemplate.asObjectable(), result);
		}
		for (ObjectTemplateItemDefinitionType templateItemDefType: objectTemplateType.getItem()) {
			ItemPathType ref = templateItemDefType.getRef();
			if (ref == null) {
				throw new SchemaException("No 'ref' in item definition in "+objectTemplateType);
			}
			ItemPath itemPath = ref.getItemPath();
			ItemDefinition itemDefFromObject = object.getDefinition().findItemDefinition(itemPath);
            if (itemDefFromObject != null) {
                applyObjectTemplateItem(itemDefFromObject, templateItemDefType, "item " + itemPath + " in " + object
                        + " as specified in item definition in " + objectTemplateType);
            } else {
                OperationResult subResult = result.createMinorSubresult(ModelController.class.getName() + ".applyObjectTemplateToObject");
                subResult.recordPartialError("No definition for item " + itemPath + " in " + object
                        + " as specified in item definition in " + objectTemplateType);
                continue;
            }
			Item<?, ?> item = object.findItem(itemPath);
			if (item != null) {
				ItemDefinition itemDef = item.getDefinition();
				if (itemDef != itemDefFromObject) {
					applyObjectTemplateItem(itemDef, templateItemDefType, "item "+itemPath+" in " + object
							+ " as specified in item definition in "+objectTemplateType);
				}
			}
			
		}
	}
	
	private <IV extends PrismValue,ID extends ItemDefinition> void applyObjectTemplateItem(ID itemDef,
			ObjectTemplateItemDefinitionType templateItemDefType, String desc) throws SchemaException {
		if (itemDef == null) {
			throw new SchemaException("No definition for "+desc);
		}
		
		String displayName = templateItemDefType.getDisplayName();
		if (displayName != null) {
			itemDef.setDisplayName(displayName);
		}
		
		Integer displayOrder = templateItemDefType.getDisplayOrder();
		if (displayOrder != null) {
			itemDef.setDisplayOrder(displayOrder);
		}
		
		Boolean emphasized = templateItemDefType.isEmphasized();
		if (emphasized != null) {
			itemDef.setEmphasized(emphasized);
		}
		
		List<PropertyLimitationsType> limitations = templateItemDefType.getLimitations();
		if (limitations != null) {
			PropertyLimitationsType limitationsType = MiscSchemaUtil.getLimitationsType(limitations, LayerType.PRESENTATION);
			if (limitationsType != null) {
				if (limitationsType.getMinOccurs() != null) {
					itemDef.setMinOccurs(XsdTypeMapper.multiplicityToInteger(limitationsType.getMinOccurs()));
				}
				if (limitationsType.getMaxOccurs() != null) {
					itemDef.setMaxOccurs(XsdTypeMapper.multiplicityToInteger(limitationsType.getMaxOccurs()));
				}
				if (limitationsType.isIgnore() != null) {
					itemDef.setIgnored(limitationsType.isIgnore());
				}
				PropertyAccessType accessType = limitationsType.getAccess();
				if (accessType != null) {
					if (accessType.isAdd() != null) {
						itemDef.setCanAdd(accessType.isAdd());
					}
					if (accessType.isModify() != null) {
						itemDef.setCanModify(accessType.isModify());
					}
					if (accessType.isRead() != null) {
						itemDef.setCanRead(accessType.isRead());
					}
				}
			}
		}
		
		ObjectReferenceType valueEnumerationRef = templateItemDefType.getValueEnumerationRef();
		if (valueEnumerationRef != null) {
			PrismReferenceValue valueEnumerationRVal = MiscSchemaUtil.objectReferenceTypeToReferenceValue(valueEnumerationRef);
			itemDef.setValueEnumerationRef(valueEnumerationRVal);
		}			
	}
	
	
	private <T extends ObjectType> void validateObject(PrismObject<T> object, GetOperationOptions options, OperationResult result) {
		try {
			if (InternalsConfig.readEncryptionChecks) {
				CryptoUtil.checkEncrypted(object);
			}
			if (!InternalsConfig.consistencyChecks) {
				return;
			}
			Class<T> type = object.getCompileTimeClass();
			boolean tolerateRaw = GetOperationOptions.isTolerateRawData(options);
			if (type == ResourceType.class || ShadowType.class.isAssignableFrom(type) || type == ReportType.class) {
				// We tolerate raw values for resource and shadows in case the user has requested so
				tolerateRaw = GetOperationOptions.isRaw(options);
			}
			if (hasError(object, result)) {
				// If there is an error then the object might not be complete.
				// E.g. we do not have a complete dynamic schema to apply to the object
				// Tolerate some raw meat in that case.
				tolerateRaw = true;
			}
			object.checkConsistence(true, !tolerateRaw, ConsistencyCheckScope.THOROUGH);
		} catch (RuntimeException e) {
			result.recordFatalError(e);
			throw e;
		}
	}
	
	private <T extends ObjectType> boolean hasError(PrismObject<T> object, OperationResult result) {
		if (result != null && result.isError()) {		// actually, result is pretty tiny here - does not include object fetch/get operation
			return true;
		}
		OperationResultType fetchResult = object.asObjectable().getFetchResult();
		if (fetchResult != null && 
				(fetchResult.getStatus() == OperationResultStatusType.FATAL_ERROR ||
				fetchResult.getStatus() == OperationResultStatusType.PARTIAL_ERROR)) {
			return true;
		}
		return false;
	}

}
