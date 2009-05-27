/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.geronimo.blueprint.container;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.RandomAccess;
import java.util.Set;

import net.sf.cglib.proxy.Dispatcher;
import org.apache.geronimo.blueprint.ExtendedBlueprintContainer;
import org.apache.geronimo.blueprint.di.Recipe;
import org.apache.geronimo.blueprint.utils.ConversionUtils;
import org.apache.geronimo.blueprint.utils.DynamicCollection;
import org.apache.geronimo.blueprint.utils.TypeUtils;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceReference;
import org.osgi.service.blueprint.container.ComponentDefinitionException;
import org.osgi.service.blueprint.container.ServiceUnavailableException;
import org.osgi.service.blueprint.reflect.RefCollectionMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A recipe to create a managed collection of service references
 *
 * @author <a href="mailto:dev@geronimo.apache.org">Apache Geronimo Project</a>
 * @version $Rev: 760378 $, $Date: 2009-03-31 11:31:38 +0200 (Tue, 31 Mar 2009) $
 */
public class RefCollectionRecipe extends AbstractServiceReferenceRecipe {

    private static final Logger LOGGER = LoggerFactory.getLogger(RefCollectionRecipe.class);

    private final RefCollectionMetadata metadata;
    private final Recipe comparatorRecipe;
    private ManagedCollection collection;
    private final List<ServiceDispatcher> unboundDispatchers = new ArrayList<ServiceDispatcher>();

    public RefCollectionRecipe(String name,
                               ExtendedBlueprintContainer blueprintContainer,
                               RefCollectionMetadata metadata,
                               Recipe listenersRecipe,
                               Recipe comparatorRecipe) {
        super(name, blueprintContainer, metadata, listenersRecipe);
        this.metadata = metadata;
        this.comparatorRecipe = comparatorRecipe;
    }

    @Override
    protected Object internalCreate() throws ComponentDefinitionException {
        Comparator comparator = null;
        try {
            if (comparatorRecipe != null) {
                comparator = (Comparator) comparatorRecipe.create();
            } else if (metadata.getOrderingBasis() != 0) {
                comparator = new NaturalOrderComparator();
            }
            boolean orderReferences = metadata.getOrderingBasis() == RefCollectionMetadata.ORDERING_BASIS_SERVICE_REFERENCE;
            Boolean memberReferences;
            if (metadata.getMemberType() == RefCollectionMetadata.MEMBER_TYPE_SERVICE_REFERENCE) {
                memberReferences = true;
            } else if (metadata.getMemberType() == RefCollectionMetadata.MEMBER_TYPE_SERVICE_INSTANCE) {
                memberReferences = false;
            } else {
                memberReferences = null;
            }
            if (metadata.getCollectionType() == List.class) {
                if (comparator != null) {
                    collection = new ManagedList(memberReferences, orderReferences, comparator);
                } else {
                    collection = new ManagedList(memberReferences);
                }
            } else if (metadata.getCollectionType() == Set.class) {
                if (comparator != null) {
                    collection = new ManagedSet(memberReferences, orderReferences, comparator);
                } else {
                    collection = new ManagedSet(memberReferences);
                }
            } else {
                throw new IllegalArgumentException("Unsupported collection type " + metadata.getCollectionType().getName());
            }

            // Handle initial references
            retrack();

            return collection;
        } catch (ComponentDefinitionException t) {
            throw t;
        } catch (Throwable t) {
            throw new ComponentDefinitionException(t);
        }
    }

    public void stop() {
        super.stop();
        if (collection != null) {
            List<ServiceDispatcher> dispatchers = new ArrayList<ServiceDispatcher>(collection.getDispatchers());
            for (ServiceDispatcher dispatcher : dispatchers) {
                untrack(dispatcher.reference);
            }
        }
    }

    protected void retrack() {
        List<ServiceReference> refs = getServiceReferences();
        if (refs != null) {
            for (ServiceReference ref : refs) {
                track(ref);
            }
        }
    }

    protected void track(ServiceReference reference) {
        if (collection != null) {
            try {
                // ServiceReferences may be tracked at multiple points:
                //  * first after the collection creation in #internalCreate()
                //  * in #postCreate() after listeners are created
                //  * after creation time if a new reference shows up
                //
                // In the first step, listeners are not created, so we add
                // the dispatcher to the unboundDispatchers list.  In the second
                // step, the dispatcher has already been added to the collection
                // so we just call the listener.
                //
                ServiceDispatcher dispatcher = collection.findDispatcher(reference);
                if (dispatcher != null) {
                    if (!unboundDispatchers.remove(dispatcher)) {
                        return;
                    }
                } else {
                    dispatcher = new ServiceDispatcher(reference);
                    dispatcher.proxy = createProxy(dispatcher, Arrays.asList((String[]) reference.getProperty(Constants.OBJECTCLASS)));
                    synchronized (collection) {
                        collection.addDispatcher(dispatcher);
                    }
                }
                if (listeners != null) {
                    for (Listener listener : listeners) {
                        if (listener != null) {
                            listener.bind(dispatcher.reference, dispatcher.proxy);
                        }
                    }
                } else {
                    unboundDispatchers.add(dispatcher);
                }
            } catch (Throwable t) {
                LOGGER.info("Error tracking new service reference", t);
            }
        }
    }

    protected void untrack(ServiceReference reference) {
        if (collection != null) {
            ServiceDispatcher dispatcher = collection.findDispatcher(reference);
            if (dispatcher != null) {
                if (listeners != null) {
                    for (Listener listener : listeners) {
                        if (listener != null) {
                            listener.unbind(dispatcher.reference, dispatcher.proxy);
                        }
                    }
                }
                synchronized (collection) {
                    collection.removeDispatcher(dispatcher);
                }
            }
            dispatcher.destroy();
        }
    }

    /**
     * The ServiceDispatcher is used when creating the cglib proxy.
     * Thic class is responsible for getting the actual service that will be used.
     */
    public class ServiceDispatcher implements Dispatcher {

        public ServiceReference reference;
        public Object service;
        public Object proxy;
        
        public ServiceDispatcher(ServiceReference reference) throws Exception {
            this.reference = reference;
        }

        public Object getMember() {
            if (collection.isMemberReferences()) {
                return reference;
            } else {
                return proxy;
            }
        }
        
        public synchronized void destroy() {
            if (reference != null) {
                reference.getBundle().getBundleContext().ungetService(reference);
                reference = null;
                service = null;
                proxy = null;
            }
        }

        public synchronized Object loadObject() throws Exception {
            if (reference == null) {
                throw new ServiceUnavailableException("Service is unavailable", null, null);
            }
            if (service == null) {
                service = reference.getBundle().getBundleContext().getService(reference);
            }
            return service;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ServiceDispatcher that = (ServiceDispatcher) o;
            if (this.getMember() != null ? !this.getMember().equals(that.getMember()) : that.getMember() != null) return false;
            return true;
        }

        @Override
        public int hashCode() {
            return getMember() != null ? getMember().hashCode() : 0;
        }
    }

    /**
     * A natural order comparator working on objects implementing Comparable
     * and simply delegating to Comparable.compareTo()
     */
    public static class NaturalOrderComparator implements Comparator<Comparable> {

        public int compare(Comparable o1, Comparable o2) {
            return o1.compareTo(o2);
        }

    }

    /**
     * A comparator to order ServiceDispatchers, sorting on references or proxies
     * depending of the configuration of the <ref-list/> or <ref-set/>
     */
    public static class DispatcherComparator implements Comparator<ServiceDispatcher> {

        private final Comparator comparator;
        private final boolean orderingReferences;

        public DispatcherComparator(Comparator comparator, boolean orderingReferences) {
            this.comparator = comparator;
            this.orderingReferences = orderingReferences;
        }

        public int compare(ServiceDispatcher d1, ServiceDispatcher d2) {
            return comparator.compare(getOrdering(d1), getOrdering(d2));
        }

        protected Object getOrdering(ServiceDispatcher d) {
            return orderingReferences ? d.reference : d.proxy;
        }

    }

    /**
     * Base class for managed collections.
     * This class implemenents the Convertible interface to detect if the collection need
     * to use ServiceReference or proxies.
     */
    public static class ManagedCollection extends AbstractCollection implements ConversionUtils.Convertible {

        protected final DynamicCollection<ServiceDispatcher> dispatchers;
        protected Boolean references;

        public ManagedCollection(Boolean references, DynamicCollection<ServiceDispatcher> dispatchers) {
            this.references = references;
            this.dispatchers = dispatchers;
            LOGGER.debug("ManagedCollection references={}", references);
        }

        public Object convert(Type type) {
            LOGGER.debug("Converting ManagedCollection to {}", type);
            if (Object.class == type) {
                return this;
            }
            if (!Collection.class.isAssignableFrom(TypeUtils.toClass(type))) {
                throw new ComponentDefinitionException("<ref-list/> and <ref-set/> can only be converted to other collections, not " + type);
            }
            if (TypeUtils.toClass(type).isInstance(this)) {
                Boolean useRef = null;
                if (type instanceof ParameterizedType) {
                    Type[] args = ((ParameterizedType) type).getActualTypeArguments();
                    if (args != null && args.length == 1) {
                        useRef = (args[0] == ServiceReference.class);
                    }
                }
                if (references == null) {
                    references = useRef != null ? useRef : false;
                    LOGGER.debug("ManagedCollection references={}", references);
                    return this;
                } else if (useRef == null || references.booleanValue() == useRef.booleanValue()) {
                    return this;
                }
                // TODO: the current collection can not be converted, so we need to create a new collection
                throw new ComponentDefinitionException("The same <ref-list/> or <ref-set/> can not be " +
                        "injected as Collection<ServiceReference> and Collection<NotServiceReference> at the same time");
            } else {
                // TODO: the current collection can not be converted, so we need to create a new collection
                throw new ComponentDefinitionException("Unsupported conversion to " + type);
            }
        }

        public boolean isMemberReferences() {
            if (references == null) {
                references = false;
            }
            LOGGER.debug("Retrieving member in ManagedCollection references={}", references);
            return references;
        }
        
        public boolean addDispatcher(ServiceDispatcher dispatcher) {
            return dispatchers.add(dispatcher);
        }

        public boolean removeDispatcher(ServiceDispatcher dispatcher) {
            return dispatchers.remove(dispatcher);
        }

        public DynamicCollection<ServiceDispatcher> getDispatchers() {
            return dispatchers;
        }

        public ServiceDispatcher findDispatcher(ServiceReference reference) {
            for (ServiceDispatcher dispatcher : dispatchers) {
                if (dispatcher.reference == reference) {
                    return dispatcher;
                }
            }
            return null;
        }

        public Iterator iterator() {
            return new ManagedIterator(dispatchers.iterator());
        }

        public int size() {
            return dispatchers.size();
        }

        @Override
        public boolean add(Object o) {
            throw new UnsupportedOperationException("This collection is read only");
        }

        @Override
        public boolean remove(Object o) {
            throw new UnsupportedOperationException("This collection is read only");
        }

        @Override
        public boolean addAll(Collection c) {
            throw new UnsupportedOperationException("This collection is read only");
        }

        @Override
        public void clear() {
            throw new UnsupportedOperationException("This collection is read only");
        }

        @Override
        public boolean retainAll(Collection c) {
            throw new UnsupportedOperationException("This collection is read only");
        }

        @Override
        public boolean removeAll(Collection c) {
            throw new UnsupportedOperationException("This collection is read only");
        }

        public class ManagedIterator implements Iterator {

            private final Iterator<ServiceDispatcher> iterator;

            public ManagedIterator(Iterator<ServiceDispatcher> iterator) {
                this.iterator = iterator;
            }

            public boolean hasNext() {
                return iterator.hasNext();
            }

            public Object next() {
                return iterator.next().getMember();
            }

            public void remove() {
                throw new UnsupportedOperationException("This collection is read only");
            }
        }

    }


    public static class ManagedList extends ManagedCollection implements List, RandomAccess {

        public ManagedList(Boolean references) {
            super(references,  new DynamicCollection<ServiceDispatcher>(true, null));
        }

        public ManagedList(Boolean references, boolean orderingReferences, Comparator comparator) {
            super(references, new DynamicCollection<ServiceDispatcher>(true, new DispatcherComparator(comparator, orderingReferences)));
        }

        @Override
        public int size() {
            return dispatchers.size();
        }

        public Object get(int index) {
            return dispatchers.get(index).getMember();
        }

        public int indexOf(Object o) {
            if (o == null) {
                throw new NullPointerException();
            }
            ListIterator e = listIterator();
            while (e.hasNext()) {
                if (o.equals(e.next())) {
                    return e.previousIndex();
                }
            }
            return -1;
        }

        public int lastIndexOf(Object o) {
            if (o == null) {
                throw new NullPointerException();
            }
            ListIterator e = listIterator(size());
            while (e.hasPrevious()) {
                if (o.equals(e.previous())) {
                    return e.nextIndex();
                }
            }
            return -1;
        }

        public ListIterator listIterator() {
            return listIterator(0);
        }

        public ListIterator listIterator(int index) {
            return new ManagedListIterator(dispatchers.iterator(index));
        }

        public List<ServiceDispatcher> subList(int fromIndex, int toIndex) {
            throw new UnsupportedOperationException("Not implemented");
        }

        public Object set(int index, Object element) {
            throw new UnsupportedOperationException("This collection is read only");
        }

        public void add(int index, Object element) {
            throw new UnsupportedOperationException("This collection is read only");
        }

        public Object remove(int index) {
            throw new UnsupportedOperationException("This collection is read only");
        }

        public boolean addAll(int index, Collection c) {
            throw new UnsupportedOperationException("This collection is read only");
        }

        public class ManagedListIterator implements ListIterator {

            protected final ListIterator<ServiceDispatcher> iterator;

            public ManagedListIterator(ListIterator<ServiceDispatcher> iterator) {
                this.iterator = iterator;
            }

            public boolean hasNext() {
                return iterator.hasNext();
            }

            public Object next() {
                return iterator.next().getMember();
            }

            public boolean hasPrevious() {
                return iterator.hasPrevious();
            }

            public Object previous() {
                return iterator.previous().getMember();
            }

            public int nextIndex() {
                return iterator.nextIndex();
            }

            public int previousIndex() {
                return iterator.previousIndex();
            }

            public void remove() {
                throw new UnsupportedOperationException("This collection is read only");
            }

            public void set(Object o) {
                throw new UnsupportedOperationException("This collection is read only");
            }

            public void add(Object o) {
                throw new UnsupportedOperationException("This collection is read only");
            }
        }

    }

    public static class ManagedSet extends ManagedCollection implements Set {

        public ManagedSet(Boolean references) {
            super(references, new DynamicCollection<ServiceDispatcher>(false, null));
        }
        
        public ManagedSet(Boolean references, boolean orderingReferences, Comparator comparator) {
            super(references, new DynamicCollection<ServiceDispatcher>(false, new DispatcherComparator(comparator, orderingReferences)));
        }

    }

}
