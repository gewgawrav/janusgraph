package com.thinkaurelius.titan.graphdb.blueprints;

import com.google.common.collect.Iterables;
import com.thinkaurelius.titan.core.*;
import com.thinkaurelius.titan.graphdb.query.BaseQuery;
import com.thinkaurelius.titan.graphdb.query.Query;
import com.thinkaurelius.titan.graphdb.query.TitanPredicate;
import com.thinkaurelius.titan.graphdb.transaction.StandardTitanTx;
import com.tinkerpop.gremlin.process.Step;
import com.tinkerpop.gremlin.process.Traversal;
import com.tinkerpop.gremlin.process.Traverser;
import com.tinkerpop.gremlin.process.graph.step.map.VertexStep;
import com.tinkerpop.gremlin.process.util.FastNoSuchElementException;
import com.tinkerpop.gremlin.process.util.TraversalHelper;
import com.tinkerpop.gremlin.structure.*;
import com.tinkerpop.gremlin.structure.util.HasContainer;

import java.util.*;
import java.util.function.Function;

/**
 * @author Matthias Broecheler (me@matthiasb.com)
 */
public class TitanVertexStep<E extends Element> extends VertexStep<E> implements HasStepFolder<Vertex,E> {

    public TitanVertexStep(Traversal traversal, Class<E> returnClass, Direction direction, int branchFactor, String... edgeLabels) {
        super(traversal, returnClass, direction, branchFactor, edgeLabels);
        this.multiQuery = ((StandardTitanTx)traversal.sideEffects().getGraph()).getGraph().getConfiguration().useMultiQuery();
        this.hasContainers = new ArrayList<>();
        this.branchFactor = branchFactor;
    }

    private TitanVertexStep(TitanVertexStep copy, Class<E> returnClass) {
        super(copy.getTraversal(), returnClass, copy.getDirection(), copy.getBranchFactor(), copy.getEdgeLabels());
        this.multiQuery = copy.multiQuery;
        this.hasContainers = copy.hasContainers;
        this.orders = copy.orders;
        this.branchFactor = copy.branchFactor;
    }

    TitanVertexStep<Vertex> makeVertexStep() {
        assert isEdgeStep();
        return new TitanVertexStep<Vertex>(this,Vertex.class);
    }

    public boolean isEdgeStep() {
        return Edge.class.isAssignableFrom(getReturnClass());
    }

    private final boolean multiQuery;
    private boolean initialized = false;

    private<Q extends BaseVertexQuery> Q makeQuery(Q query) {
        query.labels(getEdgeLabels());
        query.direction(getDirection());
        for (HasContainer condition : hasContainers) {
            if (condition.predicate instanceof Contains && condition.value==null) {
                if (condition.predicate==Contains.within) query.has(condition.key);
                else query.hasNot(condition.key);
            } else {
                query.has(condition.key, TitanPredicate.Converter.convert(condition.predicate), condition.value);
            }
        }
        for (OrderEntry order : orders) query.orderBy(order.key,order.order);
        if (branchFactor!=BaseQuery.NO_LIMIT) query.limit(branchFactor);
        return query;
    }

    private void initialize() {
        assert !initialized;
        initialized = true;
        if (multiQuery) {
            if (!starts.hasNext()) throw FastNoSuchElementException.instance();
            TitanMultiVertexQuery mquery = ((TitanTransaction)traversal.sideEffects().getGraph()).multiQuery();
            List<Traverser.Admin<Vertex>> vertices = new ArrayList<>();
            starts.forEachRemaining(v -> { vertices.add(v); mquery.addVertex(v.get()); });
            starts.add(vertices.iterator());
            assert vertices.size()>0;
            makeQuery(mquery);

            final Map<TitanVertex, Iterable<? extends TitanElement>> results =
                    (Vertex.class.isAssignableFrom(getReturnClass())) ? mquery.vertices() : mquery.edges();
            super.setFunction(v -> (Iterator<E>)results.get(v.get()).iterator());
        } else {
            super.setFunction( v -> {
                TitanVertexQuery query = makeQuery(((TitanVertex) v.get()).query());
                return (Vertex.class.isAssignableFrom(getReturnClass())) ? query.vertices().iterator() : query.edges().iterator();
            } );
        }
    }

    @Override
    protected Traverser<E> processNextStart() {
        if (!initialized) initialize();
        return super.processNextStart();
    }

    @Override
    public void reset() {
        super.reset();
        this.initialized = false;
    }


    private final List<HasContainer> hasContainers;
    private int branchFactor = BaseQuery.NO_LIMIT;
    private List<OrderEntry> orders = new ArrayList<>();


    @Override
    public void addAll(Iterable<HasContainer> has) {
        Iterables.addAll(hasContainers, has);
    }

    @Override
    public void orderBy(String key, Order order) {
        orders.add(new OrderEntry(key,order));
    }

    @Override
    public void setLimit(int limit) {
        this.branchFactor = limit;
    }

    @Override
    public int getLimit() {
        return getBranchFactor();
    }

    public boolean hasLimit() {
        return branchFactor!= Query.NO_LIMIT;
    }

    @Override
    public String toString() {
        return this.hasContainers.isEmpty() ? super.toString() : TraversalHelper.makeStepString(this, this.hasContainers);
    }

    @Override
    public int getBranchFactor() {
        return this.branchFactor;
    }

}
