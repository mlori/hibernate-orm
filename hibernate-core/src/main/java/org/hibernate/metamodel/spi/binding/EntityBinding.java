/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * Copyright (c) 2010, Red Hat Inc. or third-party contributors as
 * indicated by the @author tags or express copyright attribution
 * statements applied by the authors.  All third-party contributions are
 * distributed under license by Red Hat Inc.
 *
 * This copyrighted material is made available to anyone wishing to use, modify,
 * copy, or redistribute it subject to the terms and conditions of the GNU
 * Lesser General Public License, as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution; if not, write to:
 * Free Software Foundation, Inc.
 * 51 Franklin Street, Fifth Floor
 * Boston, MA  02110-1301  USA
 */
package org.hibernate.metamodel.spi.binding;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.hibernate.AssertionFailure;
import org.hibernate.EntityMode;
import org.hibernate.engine.spi.FilterDefinition;
import org.hibernate.internal.util.ValueHolder;
import org.hibernate.internal.util.collections.JoinedIterable;
import org.hibernate.mapping.PropertyGeneration;
import org.hibernate.metamodel.spi.domain.AttributeContainer;
import org.hibernate.metamodel.spi.domain.Entity;
import org.hibernate.metamodel.spi.domain.PluralAttribute;
import org.hibernate.metamodel.spi.domain.PluralAttributeNature;
import org.hibernate.metamodel.spi.domain.SingularAttribute;
import org.hibernate.metamodel.spi.relational.TableSpecification;
import org.hibernate.metamodel.spi.source.JpaCallbackSource;
import org.hibernate.metamodel.spi.source.MetaAttributeContext;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.property.PropertyAccessorFactory;
import org.hibernate.tuple.entity.EntityTuplizer;

/**
 * Provides the link between the domain and the relational model for an entity.
 *
 * @author Steve Ebersole
 * @author Hardy Ferentschik
 * @author Gail Badner
 */
public class EntityBinding implements AttributeBindingContainer {
	private static final String NULL_DISCRIMINATOR_MATCH_VALUE = "null";
	private static final String NOT_NULL_DISCRIMINATOR_MATCH_VALUE = "not null";

	private final EntityBinding superEntityBinding;
	private final List<EntityBinding> subEntityBindings = new ArrayList<EntityBinding>();
	private final HierarchyDetails hierarchyDetails;

	private Entity entity;
	private TableSpecification primaryTable;
	private String primaryTableName;
	private Map<String, SecondaryTable> secondaryTables = new HashMap<String, SecondaryTable>();

	private ValueHolder<Class<?>> proxyInterfaceType;

	private String jpaEntityName;

	private Class<? extends EntityPersister> customEntityPersisterClass;
	private Class<? extends EntityTuplizer> customEntityTuplizerClass;

	private String discriminatorMatchValue;

	private Set<FilterDefinition> filterDefinitions = new HashSet<FilterDefinition>();

	private MetaAttributeContext metaAttributeContext;

	private boolean lazy;
	private boolean mutable;
	private String whereFilter;
	private String rowId;

	private boolean dynamicUpdate;
	private boolean dynamicInsert;

	private int batchSize;
	private boolean selectBeforeUpdate;
	private boolean hasSubselectLoadableCollections;

	private Boolean isAbstract;

	private String customLoaderName;
	private CustomSQL customInsert;
	private CustomSQL customUpdate;
	private CustomSQL customDelete;

	private Set<String> synchronizedTableNames = new HashSet<String>();
	private Map<String, AttributeBinding> attributeBindingMap = new HashMap<String, AttributeBinding>();

	private List<JpaCallbackSource> jpaCallbackClasses = new ArrayList<JpaCallbackSource>();

	/**
	 * Used to instantiate the EntityBinding for an entity that is the root of an inheritance hierarchy
	 *
	 * @param inheritanceType The inheritance type for the hierarchy
	 * @param entityMode The entity mode used in this hierarchy.
	 */
	public EntityBinding(InheritanceType inheritanceType, EntityMode entityMode) {
		this.superEntityBinding = null;
		this.hierarchyDetails = new HierarchyDetails( this, inheritanceType, entityMode );
	}

	/**
	 * Used to instantiate the EntityBinding for an entity that is a subclass (sub-entity) in an inheritance hierarchy
	 *
	 * @param superEntityBinding The entity binding of this binding's super
	 */
	public EntityBinding(EntityBinding superEntityBinding) {
		this.superEntityBinding = superEntityBinding;
		this.superEntityBinding.subEntityBindings.add( this );
		this.hierarchyDetails = superEntityBinding.getHierarchyDetails();
	}

	public HierarchyDetails getHierarchyDetails() {
		return hierarchyDetails;
	}

	public EntityBinding getSuperEntityBinding() {
		return superEntityBinding;
	}

	public boolean isRoot() {
		return superEntityBinding == null;
	}

	public boolean isPolymorphic() {
		return superEntityBinding != null ||
				hierarchyDetails.getEntityDiscriminator() != null ||
				!subEntityBindings.isEmpty();
	}

	public boolean hasSubEntityBindings() {
		return subEntityBindings.size() > 0;
	}

	public int getSubEntityBindingClosureSpan() {
		int n = subEntityBindings.size();
		for ( EntityBinding subEntityBinding : subEntityBindings ) {
			n += subEntityBinding.getSubEntityBindingClosureSpan();
		}
		return n;
	}

	/* used for testing */
	public Iterable<EntityBinding> getDirectSubEntityBindings() {
		return subEntityBindings;
	}

	/**
	 * Returns sub-EntityBinding objects in a special 'order', most derived subclasses
	 * first. Specifically, the sub-entity bindings follow a depth-first,
	 * post-order traversal
	 *
	 * Note that the returned value excludes this entity binding.
	 *
	 * @return sub-entity bindings ordered by those entity bindings that are most derived.
	 */
	public Iterable<EntityBinding> getPostOrderSubEntityBindingClosure() {
		// TODO: why this order?
		List<Iterable<EntityBinding>> subclassIterables = new ArrayList<Iterable<EntityBinding>>( subEntityBindings.size() + 1 );
		for ( EntityBinding subEntityBinding : subEntityBindings ) {
			Iterable<EntityBinding> subSubEntityBindings = subEntityBinding.getPostOrderSubEntityBindingClosure();
			if ( subSubEntityBindings.iterator().hasNext() ) {
				subclassIterables.add( subSubEntityBindings );
			}
		}
		if ( !subEntityBindings.isEmpty() ) {
			subclassIterables.add( subEntityBindings );
		}
		return new JoinedIterable<EntityBinding>( subclassIterables );
	}

	/**
	 * Returns sub-EntityBinding ordered as a depth-first,
	 * pre-order traversal (a subclass precedes its own subclasses).
	 *
	 * Note that the returned value specifically excludes this entity binding.
	 *
	 * @return sub-entity bindings ordered as a depth-first,
	 *         pre-order traversal
	 */
	public Iterable<EntityBinding> getPreOrderSubEntityBindingClosure() {
		return getPreOrderSubEntityBindingClosure( false );
	}

	private Iterable<EntityBinding> getPreOrderSubEntityBindingClosure(boolean includeThis) {
		List<Iterable<EntityBinding>> iterables = new ArrayList<Iterable<EntityBinding>>();
		if ( includeThis ) {
			iterables.add( java.util.Collections.singletonList( this ) );
		}
		for ( EntityBinding subEntityBinding : subEntityBindings ) {
			Iterable<EntityBinding> subSubEntityBindingClosure = subEntityBinding.getPreOrderSubEntityBindingClosure(
					true
			);
			if ( subSubEntityBindingClosure.iterator().hasNext() ) {
				iterables.add( subSubEntityBindingClosure );
			}
		}
		return new JoinedIterable<EntityBinding>( iterables );
	}

	public Entity getEntity() {
		return entity;
	}

	public void setEntity(Entity entity) {
		this.entity = entity;
	}

	public TableSpecification getPrimaryTable() {
		return primaryTable;
	}

	public void setPrimaryTable(TableSpecification primaryTable) {
		this.primaryTable = primaryTable;
	}

	public TableSpecification locateTable(String tableName) {
		if ( tableName == null || tableName.equals( getPrimaryTableName() ) ) {
			return primaryTable;
		}
		SecondaryTable secondaryTable = secondaryTables.get( tableName );
		if ( secondaryTable == null ) {
			throw new AssertionFailure(
					String.format(
							"Unable to find table %s amongst tables %s",
							tableName,
							secondaryTables.keySet()
					)
			);
		}
		return secondaryTable.getSecondaryTableReference();
	}

	public String getPrimaryTableName() {
		return primaryTableName;
	}

	public void setPrimaryTableName(String primaryTableName) {
		this.primaryTableName = primaryTableName;
	}

	public void addSecondaryTable(SecondaryTable secondaryTable) {
		secondaryTables.put( secondaryTable.getSecondaryTableReference().getLogicalName().getName(), secondaryTable );
	}

	public boolean isVersioned() {
		return getHierarchyDetails().getEntityVersion().getVersioningAttributeBinding() != null;
	}

	public boolean isDiscriminatorMatchValueNull() {
		return NULL_DISCRIMINATOR_MATCH_VALUE.equals( discriminatorMatchValue );
	}

	public boolean isDiscriminatorMatchValueNotNull() {
		return NOT_NULL_DISCRIMINATOR_MATCH_VALUE.equals( discriminatorMatchValue );
	}

	public String getDiscriminatorMatchValue() {
		return discriminatorMatchValue;
	}

	public void setDiscriminatorMatchValue(String discriminatorMatchValue) {
		this.discriminatorMatchValue = discriminatorMatchValue;
	}

	public Iterable<FilterDefinition> getFilterDefinitions() {
		return filterDefinitions;
	}

	public void addFilterDefinition(FilterDefinition filterDefinition) {
		filterDefinitions.add( filterDefinition );
	}

	@Override
	public EntityBinding seekEntityBinding() {
		return this;
	}

	@Override
	public String getPathBase() {
		return getEntity().getName();
	}

	@Override
	public Class<?> getClassReference() {
		return getEntity().getClassReference();
	}

	@Override
	public AttributeContainer getAttributeContainer() {
		return getEntity();
	}

	protected void registerAttributeBinding(String name, AttributeBinding attributeBinding) {
		attributeBindingMap.put( name, attributeBinding );
	}

	@Override
	public MetaAttributeContext getMetaAttributeContext() {
		return metaAttributeContext;
	}

	public void setMetaAttributeContext(MetaAttributeContext metaAttributeContext) {
		this.metaAttributeContext = metaAttributeContext;
	}

	public boolean isMutable() {
		return mutable;
	}

	public void setMutable(boolean mutable) {
		this.mutable = mutable;
	}

	public boolean isLazy() {
		return lazy;
	}

	public void setLazy(boolean lazy) {
		this.lazy = lazy;
	}

	public ValueHolder<Class<?>> getProxyInterfaceType() {
		return proxyInterfaceType;
	}

	public void setProxyInterfaceType(ValueHolder<Class<?>> proxyInterfaceType) {
		this.proxyInterfaceType = proxyInterfaceType;
	}

	public String getWhereFilter() {
		return whereFilter;
	}

	public void setWhereFilter(String whereFilter) {
		this.whereFilter = whereFilter;
	}

	public String getRowId() {
		return rowId;
	}

	public void setRowId(String rowId) {
		this.rowId = rowId;
	}

	public boolean isDynamicUpdate() {
		return dynamicUpdate;
	}

	public void setDynamicUpdate(boolean dynamicUpdate) {
		this.dynamicUpdate = dynamicUpdate;
	}

	public boolean isDynamicInsert() {
		return dynamicInsert;
	}

	public void setDynamicInsert(boolean dynamicInsert) {
		this.dynamicInsert = dynamicInsert;
	}

	public int getBatchSize() {
		return batchSize;
	}

	public void setBatchSize(int batchSize) {
		this.batchSize = batchSize;
	}

	public boolean isSelectBeforeUpdate() {
		return selectBeforeUpdate;
	}

	public void setSelectBeforeUpdate(boolean selectBeforeUpdate) {
		this.selectBeforeUpdate = selectBeforeUpdate;
	}

	public boolean hasSubselectLoadableCollections() {
		return hasSubselectLoadableCollections;
	}

	/* package-protected */
	void setSubselectLoadableCollections(boolean hasSubselectLoadableCollections) {
		this.hasSubselectLoadableCollections = hasSubselectLoadableCollections;
	}

	public Class<? extends EntityPersister> getCustomEntityPersisterClass() {
		return customEntityPersisterClass;
	}

	public void setCustomEntityPersisterClass(Class<? extends EntityPersister> customEntityPersisterClass) {
		this.customEntityPersisterClass = customEntityPersisterClass;
	}

	public Class<? extends EntityTuplizer> getCustomEntityTuplizerClass() {
		return customEntityTuplizerClass;
	}

	public void setCustomEntityTuplizerClass(Class<? extends EntityTuplizer> customEntityTuplizerClass) {
		this.customEntityTuplizerClass = customEntityTuplizerClass;
	}

	public Boolean isAbstract() {
		return isAbstract;
	}

	public void setAbstract(Boolean isAbstract) {
		this.isAbstract = isAbstract;
	}

	public Set<String> getSynchronizedTableNames() {
		return synchronizedTableNames;
	}

	public void addSynchronizedTableNames(java.util.Collection<String> synchronizedTableNames) {
		this.synchronizedTableNames.addAll( synchronizedTableNames );
	}

	public String getJpaEntityName() {
		return jpaEntityName;
	}

	public void setJpaEntityName(String jpaEntityName) {
		this.jpaEntityName = jpaEntityName;
	}

	public String getCustomLoaderName() {
		return customLoaderName;
	}

	public void setCustomLoaderName(String customLoaderName) {
		this.customLoaderName = customLoaderName;
	}

	public CustomSQL getCustomInsert() {
		return customInsert;
	}

	public void setCustomInsert(CustomSQL customInsert) {
		this.customInsert = customInsert;
	}

	public CustomSQL getCustomUpdate() {
		return customUpdate;
	}

	public void setCustomUpdate(CustomSQL customUpdate) {
		this.customUpdate = customUpdate;
	}

	public CustomSQL getCustomDelete() {
		return customDelete;
	}

	public void setCustomDelete(CustomSQL customDelete) {
		this.customDelete = customDelete;
	}

	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder();
		sb.append( "EntityBinding" );
		sb.append( "{entity=" ).append( entity != null ? entity.getName() : "not set" );
		sb.append( '}' );
		return sb.toString();
	}

	@Override
	public BasicAttributeBinding makeBasicAttributeBinding(
			SingularAttribute attribute,
			List<RelationalValueBinding> relationalValueBindings,
			String propertyAccessorName,
			boolean includedInOptimisticLocking,
			boolean lazy,
			SingularAttributeBinding.NaturalIdMutability naturalIdMutability,
			MetaAttributeContext metaAttributeContext,
			PropertyGeneration generation) {
		final BasicAttributeBinding binding = new BasicAttributeBinding(
				this,
				attribute,
				relationalValueBindings,
				propertyAccessorName,
				includedInOptimisticLocking,
				lazy,
				naturalIdMutability,
				metaAttributeContext,
				generation
		);
		registerAttributeBinding( attribute.getName(), binding );
		return binding;
	}

	@Override
	public CompositeAttributeBinding makeComponentAttributeBinding(
			SingularAttribute attribute,
			SingularAttribute parentReferenceAttribute,
			String propertyAccessorName,
			boolean includedInOptimisticLocking,
			boolean lazy,
			SingularAttributeBinding.NaturalIdMutability naturalIdMutability,
			MetaAttributeContext metaAttributeContext) {
		final CompositeAttributeBinding binding = new CompositeAttributeBinding(
				this,
				attribute,
				propertyAccessorName,
				includedInOptimisticLocking,
				lazy,
				naturalIdMutability,
				metaAttributeContext,
				parentReferenceAttribute
		);
		registerAttributeBinding( attribute.getName(), binding );
		return binding;
	}

	public CompositeAttributeBinding makeVirtualComponentAttributeBinding(
			SingularAttribute syntheticAttribute,
			List<SingularAttributeBinding> subAttributeBindings,
			MetaAttributeContext metaAttributeContext) {
		if ( !syntheticAttribute.isSynthetic() ) {
			throw new AssertionFailure(
					"Illegal attempt to create synthetic attribute binding from non-synthetic attribute reference"
			);
		}
		final CompositeAttributeBinding binding = new CompositeAttributeBinding(
				this,
				syntheticAttribute,
				PropertyAccessorFactory.EMBEDDED_ACCESSOR_NAME,
				SingularAttributeBinding.NaturalIdMutability.NOT_NATURAL_ID,
				metaAttributeContext,
				subAttributeBindings
		);
		registerAttributeBinding( syntheticAttribute.getName(), binding );
		return binding;
	}

	public BackRefAttributeBinding makeBackRefAttributeBinding(
			SingularAttribute syntheticAttribute, PluralAttributeBinding pluralAttributeBinding) {
		if ( ! syntheticAttribute.isSynthetic() ) {
			throw new AssertionFailure(
					"Illegal attempt to create synthetic attribute binding from non-synthetic attribute reference"
			);
		}
		final BackRefAttributeBinding  binding = new BackRefAttributeBinding(
				this,
				syntheticAttribute,
				pluralAttributeBinding
		);

		registerAttributeBinding( syntheticAttribute.getName(), binding );
		return binding;
	}

	@Override
	public ManyToOneAttributeBinding makeManyToOneAttributeBinding(
			SingularAttribute attribute,
			String propertyAccessorName,
			boolean includedInOptimisticLocking,
			boolean lazy,
			SingularAttributeBinding.NaturalIdMutability naturalIdMutability,
			MetaAttributeContext metaAttributeContext,
			EntityBinding referencedEntityBinding,
			SingularAttributeBinding referencedAttributeBinding,
			List<RelationalValueBinding> valueBindings) {
		final ManyToOneAttributeBinding binding = new ManyToOneAttributeBinding(
				this,
				attribute,
				propertyAccessorName,
				includedInOptimisticLocking,
				lazy,
				naturalIdMutability,
				metaAttributeContext,
				referencedEntityBinding,
				referencedAttributeBinding,
				valueBindings
		);
		registerAttributeBinding( attribute.getName(), binding );
		return binding;
	}

	@Override
	public BagBinding makeBagAttributeBinding(
			PluralAttribute attribute,
			PluralAttributeElementNature nature,
			SingularAttributeBinding referencedAttributeBinding,
			String propertyAccessorName,
			boolean includedInOptimisticLocking,
			MetaAttributeContext metaAttributeContext) {
		Helper.checkPluralAttributeNature( attribute, PluralAttributeNature.BAG );
		final BagBinding binding = new BagBinding(
				this,
				attribute,
				nature,
				referencedAttributeBinding,
				propertyAccessorName,
				includedInOptimisticLocking,
				metaAttributeContext
		);
		registerAttributeBinding( attribute.getName(), binding );
		return binding;
	}

	@Override
	public ListBinding makeListAttributeBinding(
			PluralAttribute attribute,
			PluralAttributeElementNature nature,
			SingularAttributeBinding referencedAttributeBinding,
			String propertyAccessorName,
			boolean includedInOptimisticLocking,
			MetaAttributeContext metaAttributeContext,
			int base) {
		Helper.checkPluralAttributeNature( attribute, PluralAttributeNature.LIST );
		final ListBinding binding = new ListBinding(
				this,
				attribute,
				nature,
				referencedAttributeBinding,
				propertyAccessorName,
				includedInOptimisticLocking,
				metaAttributeContext,
				base
		);
		registerAttributeBinding( attribute.getName(), binding );
		return binding;
	}

	@Override
	public MapBinding makeMapAttributeBinding(
			PluralAttribute attribute,
			PluralAttributeElementNature elementNature,
			PluralAttributeIndexNature indexNature,
			SingularAttributeBinding referencedAttributeBinding,
			String propertyAccessorName,
			boolean includedInOptimisticLocking,
			MetaAttributeContext metaAttributeContext) {
		Helper.checkPluralAttributeNature( attribute, PluralAttributeNature.MAP );
		final MapBinding binding = new MapBinding(
				this,
				attribute,
				elementNature,
				indexNature,
				referencedAttributeBinding,
				propertyAccessorName,
				includedInOptimisticLocking,
				metaAttributeContext
		);
		registerAttributeBinding( attribute.getName(), binding );
		return binding;
	}

	@Override
	public SetBinding makeSetAttributeBinding(
			PluralAttribute attribute,
			PluralAttributeElementNature nature,
			SingularAttributeBinding referencedAttributeBinding,
			String propertyAccessorName,
			boolean includedInOptimisticLocking,
			MetaAttributeContext metaAttributeContext) {
		Helper.checkPluralAttributeNature( attribute, PluralAttributeNature.SET );
		final SetBinding binding = new SetBinding(
				this,
				attribute,
				nature,
				referencedAttributeBinding,
				propertyAccessorName,
				includedInOptimisticLocking,
				metaAttributeContext
		);
		registerAttributeBinding( attribute.getName(), binding );
		return binding;
	}

	@Override
	public AttributeBinding locateAttributeBinding(String name) {
		return attributeBindingMap.get( name );
	}

	@Override
	public AttributeBinding locateAttributeBinding(List<org.hibernate.metamodel.spi.relational.Value> values) {
		for(AttributeBinding attributeBinding : attributeBindingMap.values()) {
			if(!(attributeBinding instanceof BasicAttributeBinding)) {
				continue;
			}
			BasicAttributeBinding basicAttributeBinding = (BasicAttributeBinding) attributeBinding;

			List<org.hibernate.metamodel.spi.relational.Value> attributeValues = new ArrayList<org.hibernate.metamodel.spi.relational.Value>(  );
			for(RelationalValueBinding relationalBinding : basicAttributeBinding.getRelationalValueBindings()) {
				attributeValues.add( relationalBinding.getValue() );
			}

			if(attributeValues.equals( values )) {
				return attributeBinding;
			}
		}
		return null;
	}

	@Override
	public Iterable<AttributeBinding> attributeBindings() {
		return attributeBindingMap.values();
	}

	/**
	 * Gets the number of attribute bindings defined on this class, including the
	 * identifier attribute binding and attribute bindings defined
	 * as part of a join.
	 *
	 * @return The number of attribute bindings
	 */
	public int getAttributeBindingClosureSpan() {
		// TODO: update account for join attribute bindings
		return superEntityBinding != null ?
				superEntityBinding.getAttributeBindingClosureSpan() + attributeBindingMap.size() :
				attributeBindingMap.size();
	}

	/**
	 * Gets the attribute bindings defined on this class, including the
	 * identifier attribute binding and attribute bindings defined
	 * as part of a join.
	 *
	 * @return The attribute bindings.
	 */
	public Iterable<AttributeBinding> getAttributeBindingClosure() {
		// TODO: update size to account for joins
		Iterable<AttributeBinding> iterable;
		if ( superEntityBinding != null ) {
			List<Iterable<AttributeBinding>> iterables = new ArrayList<Iterable<AttributeBinding>>( 2 );
			iterables.add( superEntityBinding.getAttributeBindingClosure() );
			iterables.add( attributeBindings() );
			iterable = new JoinedIterable<AttributeBinding>( iterables );
		}
		else {
			iterable = attributeBindings();
		}
		return iterable;
	}

	/**
	 * @return the attribute bindings for this EntityBinding and all of its
	 *         sub-EntityBinding, starting from the root of the hierarchy; includes
	 *         the identifier and attribute bindings defined as part of a join.
	 */
	public Iterable<AttributeBinding> getSubEntityAttributeBindingClosure() {
		List<Iterable<AttributeBinding>> iterables = new ArrayList<Iterable<AttributeBinding>>();
		iterables.add( getAttributeBindingClosure() );
		for ( EntityBinding subEntityBinding : getPreOrderSubEntityBindingClosure() ) {
			// only add attribute bindings declared for the subEntityBinding
			iterables.add( subEntityBinding.attributeBindings() );
			// TODO: if EntityBinding.attributeBindings() excludes joined attributes, then they need to be added here
		}
		return new JoinedIterable<AttributeBinding>( iterables );
	}

	public void setJpaCallbackClasses(List<JpaCallbackSource> jpaCallbackClasses) {
		this.jpaCallbackClasses = jpaCallbackClasses;
	}

	public Iterable<JpaCallbackSource> getJpaCallbackClasses() {
		return jpaCallbackClasses;
	}
}