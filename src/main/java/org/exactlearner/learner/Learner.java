package org.exactlearner.learner;

import org.exactlearner.engine.BaseEngine;
import org.exactlearner.tree.ELEdge;
import org.exactlearner.tree.ELNode;
import org.exactlearner.tree.ELTree;
import org.exactlearner.utils.Metrics;
import org.semanticweb.elk.util.collections.Pair;
import org.semanticweb.owlapi.model.*;

import java.util.*;

public class Learner implements BaseLearner {

    private int unsaturationCounter = 0;
    private int saturationCounter = 0;
    private int leftDecompositionCounter = 0;
    private int rightDecompositionCounter = 0;
    private int mergeCounter = 0;
    private int branchCounter = 0;
    private final BaseEngine myEngineForT;
    private final BaseEngine myEngineForH;
    private final Metrics myMetrics;
    private OWLClassExpression myExpression;
    private OWLClass myClass;
    private ELTree leftTree;
    private ELTree rightTree;
    private final ConceptRelation<OWLClass> relation;
    private AxiomPrefetcher prefetcher;
    private boolean batchUnsaturation;

    // Speculation accounting for the unsaturate/saturate sweeps. A round is one
    // prefetch; a restart is a round forced by a mutation invalidating the last
    // one. rounds - restarts is how many sweeps ran fully off a single batch,
    // so restarts/rounds is the speculation's miss rate. Reported by
    // speculationSummary() because nothing else measures it -- the acceptance
    // rate these sweeps run at was never instrumented, and it is exactly what
    // decides whether batching them pays.
    private int speculationRounds = 0;
    private int speculationRestarts = 0;

    public Learner(BaseEngine elEngineForT, BaseEngine elEngineForH, Metrics metrics) {
        this(elEngineForT, elEngineForH, metrics, new ConceptRelation<>());
    }

    public Learner(BaseEngine elEngineForT, BaseEngine elEngineForH, Metrics metrics, ConceptRelation<OWLClass> relation) {
        myEngineForH = elEngineForH;
        myEngineForT = elEngineForT;
        myMetrics = metrics;
        this.relation = relation;
    }

    /**
     * Installs a prefetcher for the decomposition scans. Null, the default,
     * leaves every query path below exactly as it was.
     */
    public void setPrefetcher(AxiomPrefetcher prefetcher) {
        this.prefetcher = prefetcher;
    }

    /**
     * Extends prefetching to the unsaturateLeft/saturateRight sweeps. Off by
     * default, and separate from setPrefetcher, because unlike the decomposition
     * scans these sweeps are only *conditionally* independent -- see
     * prefetchUnsaturateLeftFrom -- so they carry a speculation risk the
     * signature scans do not, and the two must be measurable apart.
     */
    public void setBatchUnsaturation(boolean batchUnsaturation) {
        this.batchUnsaturation = batchUnsaturation;
    }

    /** rounds/restarts of the unsaturate/saturate speculation, for the run log. */
    public String speculationSummary() {
        return "speculation rounds=" + speculationRounds + " restarts=" + speculationRestarts;
    }

    /**
     * Hands a batch of upcoming queries to the prefetcher, if one is installed.
     *
     * A prefetch is an optimisation and nothing more: it changes when answers
     * arrive, never which questions get asked or what the scan concludes. So a
     * failure here must never take the run down. One failure disables further
     * attempts, because the causes -- no batch endpoint, no cache, an engine
     * that is not an LLMEngine -- are all permanent for the rest of the run, and
     * retrying every scan would bury the log.
     */
    private void prefetch(List<OWLAxiom> upcoming) {
        if (prefetcher == null || upcoming.isEmpty()) {
            return;
        }
        try {
            prefetcher.prefetch(upcoming);
        } catch (Throwable t) {
            System.out.println("Decomposition prefetch failed, continuing sequentially: " + t);
            prefetcher = null;
        }
    }

    /**
     * Warms a scan that holds the left side fixed and runs over the signature,
     * as decompose() and decomposingLeft() both do.
     *
     * Only axioms the hypothesis fails to entail are included. isCounterExample
     * asks the hypothesis first and reaches the model only when that check
     * fails, so anything the hypothesis already entails would be paid for and
     * never asked. The hypothesis engine is a local reasoner, so filtering here
     * is free, and no membership counter is touched -- the scan below still does
     * all its own counting.
     */
    private void prefetchLeftScan(OWLClassExpression left, List<OWLClass> classes) {
        if (prefetcher == null) {
            return;
        }
        List<OWLAxiom> upcoming = new ArrayList<>();
        for (OWLClass cl : classes) {
            if (!myEngineForH.entailed(myEngineForH.getSubClassAxiom(left, cl))) {
                upcoming.add(myEngineForT.getSubClassAxiom(left, cl));
            }
        }
        prefetch(upcoming);
    }

    /**
     * Warms decomposingRight()'s sweep over one node's edges and labels.
     *
     * That sweep only changes the tree when it finds something, and it stops
     * sweeping the moment it does -- it either removes the edge and breaks out
     * of the label loop, or returns. So until the first hit every question in
     * the edge-by-label product is asked against the same unmodified tree, which
     * makes the whole product safe to fetch ahead.
     *
     * Both questions per pair are included: the root equivalence check and the
     * subclass check. Neither is guarded by the hypothesis -- the hypothesis is
     * only consulted after the target has already answered -- so unlike the
     * signature scans there is nothing to filter out here. Answers past the
     * first hit go unused by this sweep, but they are in the cache, so they cost
     * their fetch once and stay available to every later query.
     */
    private void prefetchRightDecomposition(OWLClass cl, ELNode nod, List<ELEdge> edges) {
        if (prefetcher == null) {
            return;
        }
        List<OWLAxiom> upcoming = new ArrayList<>();
        for (ELEdge edge : edges) {
            for (OWLClass c : nod.getLabel()) {
                if (nod.isRoot()) {
                    upcoming.add(myEngineForT.getOWLEquivalentClassesAxiom(cl, c));
                }
                upcoming.add(myEngineForT.getSubClassAxiom(c, edge.transformToDescription()));
            }
        }
        prefetch(upcoming);
    }

    /**
     * Speculates the rest of one unsaturateLeft node sweep.
     *
     * These sweeps are dependent, which is why they were left sequential when
     * the decomposition scans were batched -- but only *when a removal is
     * accepted*. On rejection the label goes straight back, so the tree the next
     * question is asked against is the one standing right now. Assuming every
     * remaining removal is rejected therefore predicts the exact axioms the
     * sweep will ask for, and the assumption breaks only on an acceptance, at
     * which point the caller marks the speculation stale and this runs again
     * from the new state. Rejection is the common case, so most sweeps run
     * entirely off one batch; speculationSummary() reports how often that holds.
     *
     * Each candidate is built by making the same remove/restore pair the sweep
     * itself makes, so the axiom is identical to the one it will ask about
     * rather than a reconstruction that might render differently.
     *
     * ELNode.extendLabel bumps ELTree's size counter and remove() does not put
     * it back, so this round-trip inflates that field -- exactly as every
     * rejection in the real sweep already does. Nothing reads it: ELTree.getSize
     * has no caller outside the setter. If that ever changes, this is one of the
     * places that has to start restoring it.
     */
    private boolean prefetchUnsaturateLeftFrom(ELNode nod, List<OWLClass> classes, int from) {
        if (prefetcher == null || !batchUnsaturation) {
            return false;
        }
        speculationRounds++;
        OWLClassExpression right = rightTree.transformToClassExpression();
        List<OWLAxiom> upcoming = new ArrayList<>();
        for (int k = from; k < classes.size(); k++) {
            OWLClass cl1 = classes.get(k);
            if (nod.getLabel().contains(cl1) && !cl1.toString().contains("Thing")) {
                nod.remove(cl1);
                upcoming.add(myEngineForT.getSubClassAxiom(leftTree.transformToClassExpression(), right));
                nod.extendLabel(cl1);
            }
        }
        prefetch(upcoming);
        return true;
    }

    /**
     * The mirror of the above for saturateRight's non-root sweep, which is the
     * larger of the two: it walks the whole class signature per node, so one
     * node is ~131 questions where an unsaturation node is a handful.
     *
     * Same conditional independence -- a label that fails to hold is removed
     * again immediately, leaving the tree as it was. Root nodes are skipped
     * because that branch only consults myEngineForH, a local reasoner, and
     * costs nothing to answer.
     */
    private boolean prefetchSaturateRightFrom(ELNode nod, List<OWLClass> classes, int from) {
        if (prefetcher == null || !batchUnsaturation || nod.isRoot()) {
            return false;
        }
        speculationRounds++;
        OWLClassExpression left = leftTree.transformToClassExpression();
        List<OWLAxiom> upcoming = new ArrayList<>();
        for (int k = from; k < classes.size(); k++) {
            OWLClass cl1 = classes.get(k);
            if (!nod.getLabel().contains(cl1)) {
                nod.extendLabel(cl1);
                upcoming.add(myEngineForT.getSubClassAxiom(left, rightTree.transformToClassExpression()));
                nod.remove(cl1);
            }
        }
        prefetch(upcoming);
        return true;
    }

    /** The mirror of prefetchLeftScan for decompose()'s right-hand scan. */
    private void prefetchRightScan(List<OWLClass> classes, OWLClassExpression right) {
        if (prefetcher == null) {
            return;
        }
        List<OWLAxiom> upcoming = new ArrayList<>();
        for (OWLClass cl : classes) {
            if (!myEngineForH.entailed(myEngineForH.getSubClassAxiom(cl, right))) {
                upcoming.add(myEngineForT.getSubClassAxiom(cl, right));
            }
        }
        prefetch(upcoming);
    }

    /**
     * @param left  class expression on the left of an inclusion
     * @param right class expression on the right of an inclusion
     * @author anaozaki Naive algorithm to return a counterexample where one of the
     * sides is a concept name
     */
    @Override
    public OWLSubClassOfAxiom decompose(OWLClassExpression left, OWLClassExpression right) throws Exception {

        ELTree treeR = new ELTree(right);
        ELTree treeL = new ELTree(left);

        // Neither tree is modified anywhere below, and nothing here touches the
        // hypothesis, so each node's sweep over the signature is a set of
        // independent questions that happens to be asked in sequence. That is
        // the whole basis for prefetching them: the sweep still runs exactly as
        // written and still returns its first hit in signature order.
        for (int i = 0; i < treeL.getMaxLevel(); i++) {

            for (ELNode nod : treeL.getNodesOnLevel(i + 1)) {

                prefetchLeftScan(nod.transformToDescription(), myEngineForT.getClassesInSignature());

                for (OWLClass cl : myEngineForT.getClassesInSignature()) {
                    myMetrics.setMembCount(myMetrics.getMembCount() + 1);
                    if (isCounterExample(nod.transformToDescription(), cl)) {
                        leftDecompositionCounter++;
                        return myEngineForT.getSubClassAxiom(nod.transformToDescription(), cl);
                    }
                }
            }
        }

        for (int i = 0; i < treeR.getMaxLevel(); i++) {

            for (ELNode nod : treeR.getNodesOnLevel(i + 1)) {

                prefetchRightScan(myEngineForT.getClassesInSignature(), nod.transformToDescription());

                for (OWLClass cl : myEngineForT.getClassesInSignature()) {
                    myMetrics.setMembCount(myMetrics.getMembCount() + 1);
                    if (isCounterExample(cl, nod.transformToDescription())) {
                        rightDecompositionCounter++;
                        return myEngineForT.getSubClassAxiom(cl, nod.transformToDescription());
                    }
                }
            }
        }
        System.out.println(
                "Error decomposing. Not an EL Terminology: " + left.toString() + "subclass of" + right.toString());
        return myEngineForT.getSubClassAxiom(left, right);

    }

    private void saturateHypothesisLeft(OWLClassExpression expression, OWLClass cl) throws Exception {
        ELTree oldTree = new ELTree(expression);
        this.leftTree = new ELTree(expression);
        this.rightTree = new ELTree(cl);
        if (leftTree.getMaxLevel() > 1) {

            for (int i = 0; i < leftTree.getMaxLevel(); i++) {
                for (ELNode nod : leftTree.getNodesOnLevel(i + 1)) {
                    for (OWLClass cl1 : myEngineForH.getClassesInSignature()) {
                        if (!nod.getLabel().contains(cl1)) {
                            nod.extendLabel(cl1);
                            if (myEngineForH.entailed(myEngineForH.getSubClassAxiom(
                                    oldTree.transformToClassExpression(), leftTree.transformToClassExpression()))) {
                                oldTree = new ELTree(leftTree.transformToClassExpression());
                            } else {
                                nod.remove(cl1);
                            }
                        }
                    }
                }
            }
        }
        myExpression = leftTree.transformToClassExpression();
        myClass = (OWLClass) rightTree.transformToClassExpression();
        myEngineForT.getSubClassAxiom(myExpression, myClass);
    }

    @Override
    public OWLSubClassOfAxiom decomposeLeft(OWLClassExpression expression, OWLClass cl) throws Exception {
        myClass = cl;
        myExpression = expression;

        saturateHypothesisLeft(myExpression, myClass);

        while (decomposingLeft(myExpression)) {
        }
        return myEngineForT.getSubClassAxiom(myExpression, myClass);
    }

    private Boolean decomposingLeft(OWLClassExpression expression) throws Exception {
        ELTree tree = new ELTree(expression);
        for (int i = 0; i < tree.getMaxLevel(); i++) {
            for (ELNode nod : tree.getNodesOnLevel(i + 1)) {
                if (!nod.isRoot()) {
                    prefetchLeftScan(nod.transformToDescription(), myEngineForT.getClassesInSignature());
                    for (OWLClass cls : myEngineForT.getClassesInSignature()) {
                        myMetrics.setMembCount(myMetrics.getMembCount() + 1);
                        if (isCounterExample(nod.transformToDescription(), cls)) {
                            myExpression = nod.transformToDescription();
                            myClass = cls;
                            leftDecompositionCounter++;
                            return true;
                        }
                    }
                }
                for (int j = 0; j < nod.getEdges().size(); j++) {
                    ELTree oldTree = new ELTree(tree.transformToClassExpression());

                    nod.getEdges().remove(j);
                    if (!myEngineForT.entailed(myEngineForT.getSubClassAxiom(tree.transformToClassExpression(),
                            oldTree.transformToClassExpression()))) {// we are removing things with top, this check is
                        // to avoid loop
                        // The edge removal above is the last change to the tree
                        // before this sweep, so the sweep itself sees a fixed
                        // left-hand side and its questions are independent.
                        prefetchLeftScan(tree.transformToClassExpression(), myEngineForT.getClassesInSignature());
                        for (OWLClass cls : myEngineForT.getClassesInSignature()) {
                            myMetrics.setMembCount(myMetrics.getMembCount() + 1);
                            if (isCounterExample(tree.transformToClassExpression(), cls)) {
                                myExpression = tree.transformToClassExpression();
                                myClass = cls;
                                leftDecompositionCounter++;
                                return true;
                            }
                        }
                    }
                    tree = oldTree;
                }

            }
        }
        return false;
    }

    private void saturateHypothesisRight(OWLClass cl, OWLClassExpression expression) throws Exception {
        ELTree oldTree = new ELTree(expression);
        this.leftTree = new ELTree(cl);
        this.rightTree = new ELTree(expression);
        if (rightTree.getMaxLevel() > 1) {
            for (int i = 0; i < rightTree.getMaxLevel(); i++) {
                for (ELNode nod : rightTree.getNodesOnLevel(i + 1)) {
                    if (nod.isRoot()) {
                        for (OWLClass cl1 : myEngineForT.getClassesInSignature()) {
                            if (!nod.getLabel().contains(cl1) && !cl1.equals(cl)) {
                                if (myEngineForH.entailed(myEngineForH.getSubClassAxiom(cl, cl1))) {
                                    nod.extendLabel(cl1);
                                }
                            }
                        }
                    }
                    oldTree = new ELTree(rightTree.transformToClassExpression());
                    for (OWLClass cl1 : myEngineForH.getClassesInSignature()) {
                        if (!nod.getLabel().contains(cl1)) {
                            nod.extendLabel(cl1);
                            if (myEngineForH.entailed(myEngineForH.getSubClassAxiom(
                                    oldTree.transformToClassExpression(), rightTree.transformToClassExpression()))) {
                                oldTree = new ELTree(rightTree.transformToClassExpression());
                            } else {
                                nod.remove(cl1);
                            }
                        }
                    }
                }
            }
        }
        myClass = (OWLClass) leftTree.transformToClassExpression();
        myExpression = rightTree.transformToClassExpression();
        myEngineForT.getSubClassAxiom(myClass, myExpression);
    }

    @Override
    public OWLSubClassOfAxiom decomposeRight(OWLClass cl, OWLClassExpression expression) throws Exception {
        myClass = cl;
        myExpression = expression;
        saturateHypothesisRight(myClass, myExpression);
        // Check if a pair of myClass and myExpression has been previously found
        // In this case there is a loop and we should stop
        List<Pair<OWLClass, OWLClassExpression>> visited = new ArrayList<>();
        visited.add(new Pair<>(myClass, myExpression));
        while (decomposingRight(myClass, myExpression)) {
            if (visited.contains(new Pair<>(myClass, myExpression))) {
                break;
            }
            visited.add(new Pair<>(myClass, myExpression));
            saturateHypothesisRight(myClass, myExpression);
        }
        return myEngineForT.getSubClassAxiom(myClass, myExpression);
    }


    private boolean decomposingRight(OWLClass cl, OWLClassExpression expression) throws Exception {
        int startCount = rightDecompositionCounter;
        ELTree tree = new ELTree(expression);
        for (int i = 0; i < tree.getMaxLevel(); i++) {
            for (ELNode nod : tree.getNodesOnLevel(i + 1)) {
                List<ELEdge> edges = new ArrayList<>(nod.getEdges());
                prefetchRightDecomposition(cl, nod, edges);
                for (ELEdge edge : edges) { // Iterates through all the edges of the current node
                    for (OWLClass c : nod.getLabel()) { // Iterates through all the labels of the current node
                        if (nod.isRoot()) {
                            // If it is the root node, the selected label should not be the same as the left concept of the axiom
                            myMetrics.setMembCount(myMetrics.getMembCount() + 1);
                            if (myEngineForT.entailed(myEngineForT.getOWLEquivalentClassesAxiom(cl, c))) {
                                continue;
                            }
                        }
                        OWLSubClassOfAxiom axiom = myEngineForT.getSubClassAxiom(c, edge.transformToDescription());
                        myMetrics.setMembCount(myMetrics.getMembCount() + 1);

                        // If the new axiom is not entailed from the target ontology, we don't do anything with it.
                        if (!myEngineForT.entailed(axiom)) {
                            continue;
                        }

                        if (myEngineForH.entailed(axiom)) {
                            // If the axiom is entailed from the hypothesis ontology, it is removed from the node
                            nod.remove(edge);
                            rightDecompositionCounter++;
                            myExpression = tree.transformToClassExpression();
                            break;
                        } else {
                            // If the axiom is not entailed from the hypothesis, it becomes the new counter example
                            myExpression = axiom.getSuperClass();
                            myClass = c;
                            rightDecompositionCounter++;
                            return true;
                        }
                    }
                }
            }
        }
        return rightDecompositionCounter > startCount;
    }

    /**
     * @param expression class expression on the left of an inclusion
     * @param cl         class name on the right of an inclusion
     * @author anaozaki Concept Unsaturation on the left side of the inclusion
     */
    @Override
    public OWLSubClassOfAxiom unsaturateLeft(OWLClassExpression expression, OWLClass cl) throws Exception {
        this.leftTree = new ELTree(expression);
        this.rightTree = new ELTree(cl);
        myExpression = leftTree.transformToClassExpression();
        myClass = (OWLClass) rightTree.transformToClassExpression();
        for (int i = 0; i < leftTree.getMaxLevel(); i++) {
            List<ELNode> nodesList = leftTree.getNodesOnLevel(i + 1);
            for (ELNode nod : nodesList) {
                List<OWLClass> classesList = relation.topologicalOrder(nod.getLabel());
                // Indexed rather than for-each so a stale speculation can be
                // rebuilt from the position the sweep has actually reached.
                // Anything already examined must not be re-speculated: after an
                // acceptance the tree differs, so those axioms would be fresh
                // questions the sweep will never ask.
                boolean stale = true;
                for (int k = 0; k < classesList.size(); k++) {
                    OWLClass cl1 = classesList.get(k);
                    if (nod.getLabel().contains(cl1) && !cl1.toString().contains("Thing")) {
                        if (stale && prefetchUnsaturateLeftFrom(nod, classesList, k)) {
                            stale = false;
                        }
                        nod.remove(cl1);
                        myMetrics.setMembCount(myMetrics.getMembCount() + 1);
                        if (myEngineForT.entailed(myEngineForT.getSubClassAxiom(leftTree.transformToClassExpression(),
                                rightTree.transformToClassExpression()))) {
                            myExpression = leftTree.transformToClassExpression();
                            myClass = (OWLClass) rightTree.transformToClassExpression();

                            unsaturationCounter++;
                            // The tree moved, so the rest of the batch answers
                            // questions about a tree that no longer exists.
                            if (!stale) {
                                speculationRestarts++;
                            }
                            stale = true;
                        } else {
                            nod.extendLabel(cl1);
                        }
                    }
                }
            }
        }
        return myEngineForT.getSubClassAxiom(myExpression, myClass);
    }

    /**
     * @param cl         class name on the left of an inclusion
     * @param expression class expression on the right of an inclusion
     * @author anaozaki Concept Saturation on the right side of the inclusion
     */
    @Override
    public OWLSubClassOfAxiom saturateRight(OWLClass cl, OWLClassExpression expression) throws Exception {
        this.leftTree = new ELTree(cl);
        this.rightTree = new ELTree(expression);
        for (int i = 0; i < rightTree.getMaxLevel(); i++) {
            for (ELNode nod : rightTree.getNodesOnLevel(i + 1)) {
                List<OWLClass> signature = myEngineForT.getClassesInSignature();
                boolean stale = true;
                for (int k = 0; k < signature.size(); k++) {
                    OWLClass cl1 = signature.get(k);
                    if (!nod.getLabel().contains(cl1)) {
                        if (nod.isRoot()) {
                            if (cl1.equals(cl)) {
                                continue;
                            }
                            OWLSubClassOfAxiom axiom = myEngineForH.getSubClassAxiom(cl, cl1);
                            if (myEngineForH.entailed(axiom)) {
                                nod.extendLabel(cl1);
                                saturationCounter++;
                            } // No need to check other cases, as it should be in the hypothesis because of precomputation
                        } else {
                            if (stale && prefetchSaturateRightFrom(nod, signature, k)) {
                                stale = false;
                            }
                            nod.extendLabel(cl1);
                            myMetrics.setMembCount(myMetrics.getMembCount() + 1);
                            if (myEngineForT.entailed(myEngineForT.getSubClassAxiom(leftTree.transformToClassExpression(),
                                    rightTree.transformToClassExpression()))) {
                                saturationCounter++;
                                // Label kept, so the tree the rest of the batch
                                // assumed is gone.
                                if (!stale) {
                                    speculationRestarts++;
                                }
                                stale = true;
                            } else {
                                nod.remove(cl1);
                            }
                        }
                    }
                }
            }
        }
        myClass = (OWLClass) leftTree.transformToClassExpression();
        myExpression = rightTree.transformToClassExpression();
        return myEngineForT.getSubClassAxiom(myClass, myExpression);
    }

    @Override
    public OWLSubClassOfAxiom mergeRight(OWLClass cl, OWLClassExpression expression) throws Exception {
        myClass = cl;
        myExpression = expression;
        while (merging(myClass, myExpression)) {
        }
        return myEngineForT.getSubClassAxiom(myClass, myExpression);
    }

    private Boolean merging(OWLClass cl, OWLClassExpression expression) throws Exception {

        ELTree tree = new ELTree(expression);


        for (int i = 0; i < tree.getMaxLevel(); i++) {
            int l1 = 0;
            for (ELNode nod : tree.getNodesOnLevel(i + 1)) {

                if (!nod.getEdges().isEmpty() && nod.getEdges().size() > 1) {

                    for (int j = 0; j < nod.getEdges().size(); j++) {

                        for (int k = 0; k < nod.getEdges().size(); k++) {

                            if (j != k && nod.getEdges().get(j).getStrLabel()
                                    .equals(nod.getEdges().get(k).getStrLabel())) {
                                ELTree tmp = new ELTree(tree.transformToClassExpression());
                                List<ELNode> set = tmp.getNodesOnLevel(i + 1);
                                ELNode n = set.iterator().next();
                                for (int i1 = 0; i1 < l1; i1++) {
                                    n = set.iterator().next();
                                }
                                n.getEdges().get(j).getNode().getLabel()
                                        .addAll(n.getEdges().get(k).getNode().getLabel());

                                if (!n.getEdges().get(k).getNode().getEdges().isEmpty())
                                    n.getEdges().get(j).getNode().getEdges()
                                            .addAll(n.getEdges().get(k).getNode().getEdges());

                                n.getEdges().remove(n.getEdges().get(k));

                                myMetrics.setMembCount(myMetrics.getMembCount() + 1);
                                if (!myEngineForT.entailed(myEngineForT.getSubClassAxiom(
                                        tree.transformToClassExpression(), tmp.transformToClassExpression()))
                                        // if the merged tree is in fact a stronger expression
                                        && myEngineForT.entailed(
                                        myEngineForT.getSubClassAxiom(cl, tmp.transformToClassExpression()))) {
                                    myExpression = tmp.transformToClassExpression();
                                    myClass = cl;
                                    mergeCounter++;

                                    return true;
                                }

                            }
                        }
                    }
                }
                l1++;
            }
        }
        return false;
    }

    @Override
    public OWLSubClassOfAxiom branchLeft(OWLClassExpression expression, OWLClass cl) throws Exception {
        myClass = cl;
        myExpression = expression;
        ELTree tree = new ELTree(expression);
        for (int i = 0; i < tree.getMaxLevel(); i++) {
            for (ELNode nod : tree.getNodesOnLevel(i + 1)) {
                if (!nod.getEdges().isEmpty()) {

                    for (int j = 0; j < nod.getEdges().size(); j++) {
                        if (nod.getEdges().get(j).getNode().getLabel().size() > 1) {
                            TreeSet<OWLClass> s = new TreeSet<>(nod.getEdges().get(j).getNode().getLabel());
                            for (OWLClass lab : s) {
                                ELTree oldTree = new ELTree(tree.transformToClassExpression());
                                ELTree newSubtree = new ELTree(
                                        nod.getEdges().get(j).getNode().transformToDescription());
                                TreeSet<OWLClass> ts = new TreeSet<>(newSubtree.getRootNode().getLabel());
                                for (OWLClass l : ts) {
                                    newSubtree.getRootNode().getLabel().remove(l);
                                }
                                newSubtree.getRootNode().extendLabel(lab);
                                ELEdge newEdge = new ELEdge(nod.getEdges().get(j).getLabel(), newSubtree.getRootNode());
                                nod.getEdges().add(newEdge);
                                nod.getEdges().get(j).getNode().remove(lab);
                                myMetrics.setMembCount(myMetrics.getMembCount() + 1);
                                if (!myEngineForT.entailed(myEngineForT.getSubClassAxiom(
                                        tree.transformToClassExpression(), oldTree.transformToClassExpression()))
                                        && myEngineForT.entailed(
                                        myEngineForT.getSubClassAxiom(tree.transformToClassExpression(), cl))) {
                                    myExpression = tree.transformToClassExpression();
                                    myClass = cl;

                                    branchCounter++;
                                } else {
                                    tree = oldTree;
                                }
                            }
                        }
                    }

                }
            }
        }
        return myEngineForT.getSubClassAxiom(myExpression, myClass);
    }

    // at the moment duplicated
    // @Todo: @Riccardo please check if this is correct.
    private Boolean isCounterExample(OWLClassExpression left, OWLClassExpression right) {
        return !myEngineForH.entailed(myEngineForH.getSubClassAxiom(left, right)) &&
                myEngineForT.entailed(myEngineForT.getSubClassAxiom(left, right));
    }

    @Override
    public int getNumberUnsaturations() {
        return unsaturationCounter;
    }

    @Override
    public int getNumberSaturations() {
        return saturationCounter;
    }

    @Override
    public int getNumberMerging() {
        return mergeCounter;
    }

    @Override
    public int getNumberBranching() {
        return branchCounter;
    }

    @Override
    public int getNumberLeftDecomposition() {
        return leftDecompositionCounter;
    }

    @Override
    public int getNumberRightDecomposition() {
        return rightDecompositionCounter;
    }

    @Override
    public void minimiseHypothesis(BaseEngine elQueryEngineForH, OWLOntology hypothesisOntology) {
        Set<OWLAxiom> tmpaxiomsH = elQueryEngineForH.getOntology().getAxioms();
        Iterator<OWLAxiom> ineratorMinH = tmpaxiomsH.iterator();

        if (tmpaxiomsH.size() > 1) {
            while (ineratorMinH.hasNext()) {
                OWLAxiom checkedAxiom = ineratorMinH.next();

                if (checkedAxiom.isOfType(AxiomType.SUBCLASS_OF)) {
                    OWLSubClassOfAxiom axiom = (OWLSubClassOfAxiom) checkedAxiom;
                    OWLClassExpression left = axiom.getSubClass();
                    OWLClassExpression right = axiom.getSuperClass();

                    if (elQueryEngineForH
                            .entailed(elQueryEngineForH.getSubClassAxiom(right, left))) {
                        RemoveAxiom removedAxiom = new RemoveAxiom(elQueryEngineForH.getOntology(),
                                checkedAxiom);
                        elQueryEngineForH.applyChange(removedAxiom);
                        checkedAxiom = elQueryEngineForH.getOWLEquivalentClassesAxiom(left, right);

                        AddAxiom addAxiomtoH = new AddAxiom(hypothesisOntology, checkedAxiom);

                        elQueryEngineForH.applyChange(addAxiomtoH);
                    }
                }
                RemoveAxiom removedAxiom = new RemoveAxiom(elQueryEngineForH.getOntology(),
                        checkedAxiom);
                elQueryEngineForH.applyChange(removedAxiom);

                if (!elQueryEngineForH.entailed(checkedAxiom)) {
                    // minimize and put it back
                    checkedAxiom = minimizeAxiom(checkedAxiom);

                    AddAxiom addAxiomtoH = new AddAxiom(hypothesisOntology, checkedAxiom);
                    elQueryEngineForH.applyChange(addAxiomtoH);
                }

            }
        }

    }

    @Override
    public void precomputation() {
        int i = myEngineForT.getClassesInSignature().size();
        myMetrics.setMembCount(myMetrics.getMembCount() + i * (i - 1));
        // How much of the final hypothesis precomputation is responsible for.
        // Without this the exhaustive O(n^2) pass is invisible in the log and
        // its contribution cannot be separated from the sampling loop's.
        // Counted per pair, not per addHypothesis() call: a pair entailed by
        // BOTH H and T calls addHypothesis twice, so incrementing on each
        // branch would overstate the total.
        int addedFromH = 0;
        int addedFromT = 0;
        int addedPairs = 0;
        for (OWLClass cl1 : myEngineForT.getClassesInSignature()) {
            for (OWLClass cl2 : myEngineForT.getClassesInSignature()) {
                if (cl1.equals(cl2)) {
                    continue;
                }
                OWLSubClassOfAxiom addedAxiom = myEngineForT.getSubClassAxiom(cl1, cl2);
                boolean added = false;
                if (myEngineForH.entailed(addedAxiom)) {
                    addHypothesis(addedAxiom);
                    addedFromH++;
                    added = true;
                }
                if (myEngineForT.entailed(addedAxiom)) {
                    relation.addEdge(cl1, cl2);
                    addHypothesis(addedAxiom);
                    addedFromT++;
                    added = true;
                }
                if (added) {
                    addedPairs++;
                }
            }
        }
        System.out.println("PRECOMPUTATION: " + addedPairs + " of " + (i * (i - 1))
                + " ordered class pairs contributed an axiom (" + i + " classes); "
                + "entailed by H: " + addedFromH + ", entailed by T: " + addedFromT);
    }

    private OWLAxiom minimizeAxiom(OWLAxiom checkedAxiom) {

        if (checkedAxiom.isOfType(AxiomType.SUBCLASS_OF)) {

            checkedAxiom = minimizeRightConcept(((OWLSubClassOfAxiom) checkedAxiom).getSubClass(),
                    ((OWLSubClassOfAxiom) checkedAxiom).getSuperClass());

        }
        return checkedAxiom;
    }

    private OWLSubClassOfAxiom minimizeRightConcept(OWLClassExpression leftExpr, OWLClassExpression rightExpr) {

        ELTree tree;
        try {
            tree = new ELTree(rightExpr);

            for (int i = 0; i < tree.getMaxLevel(); i++) {
                for (ELNode nod : tree.getNodesOnLevel(i + 1)) {
                    OWLClassExpression cls = nod.transformToDescription();
                    for (OWLClass cl1 : cls.getClassesInSignature()) {
                        if ((nod.getLabel().contains(cl1) && !cl1.toString().contains("Thing"))) {
                            nod.remove(cl1);
                            if (myEngineForH.entailed(
                                    myEngineForH.getSubClassAxiom(tree.transformToClassExpression(), rightExpr))) {
                            } else {
                                nod.extendLabel(cl1);
                            }
                        }
                    }
                }
            }
            myExpression = tree.transformToClassExpression();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return myEngineForH.getSubClassAxiom(leftExpr, myExpression);
    }

    private void addHypothesis(OWLSubClassOfAxiom axiom) {
        OWLAxiomChange add = new AddAxiom(myEngineForH.getOntology(), axiom);
        myEngineForH.applyChange(add);
    }
}