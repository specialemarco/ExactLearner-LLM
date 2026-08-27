package org.exactlearner.learner;

import java.util.*;

public class ConceptRelation<T> {
    private final Map<T, ConceptRelationNode<T>> map = new HashMap<>();
    private List<T> ordered = null;

    public ConceptRelation() {}

    public void addEdge(T sub, T to) {
        ordered = null;
        if (!map.containsKey(sub)) {
            map.put(sub, new ConceptRelationNode<>(sub));
        }
        if (!map.containsKey(to)) {
            map.put(to, new ConceptRelationNode<>(to));
        }
        addEdge(map.get(sub), map.get(to));
    }

    private void addEdge(ConceptRelationNode<T> sub, ConceptRelationNode<T> sup) {
        if (sub == sup) {
            return;
        }
        List<ConceptRelationNode<T>> nodes = sup.getPossiblePath(sub);
        if (nodes.isEmpty()) {
            sub.addParent(sup);
            sup.addChild(sub);
        } else {
            merge(nodes);
        }
    }

    public void merge(List<ConceptRelationNode<T>> nodes) {
        List<ConceptRelationNode<T>> children = nodes.stream()
                .flatMap(n -> n.getChildren().stream())
                .filter(c -> !nodes.contains(c))
                .distinct()
                .toList();

        List<ConceptRelationNode<T>> parent = nodes.stream()
                .flatMap(n -> n.getParents().stream())
                .filter(c -> !nodes.contains(c))
                .distinct()
                .toList();

        List<T> labels = nodes.stream()
                .flatMap(n -> n.getNodeLabels().stream())
                .distinct()
                .toList();

        ConceptRelationNode<T> sn = new ConceptRelationNode<>(labels, children, parent);

        children.forEach(c -> {
            c.getParents().removeAll(nodes);
            c.addParent(sn);
        });

        parent.forEach(p -> {
            p.getChildren().removeAll(nodes);
            p.addChild(sn);
        });

        nodes.stream().flatMap(n -> n.getNodeLabels().stream()).forEach(c -> map.put(c, sn));
    }

    public List<T> getAllAncestors(T c) {
        ConceptRelationNode<T> node = map.get(c);
        if (node == null) {
            return new ArrayList<>();
        }
        return node.getAllAncestors();
    }

    public List<T> getAllAncestorsAndEqual(T c) {
        ConceptRelationNode<T> node = map.get(c);
        if (node == null) {
            return new ArrayList<>();
        }
        List<T> ancestors = node.getAllAncestors();
        ancestors.addAll(node.getNodeLabels().stream().filter(con -> !con.equals(c)).toList());
        return ancestors;
    }

    public List<T> getAllDescendants(T c) {
        ConceptRelationNode<T> node = map.get(c);
        if (node == null) {
            return new ArrayList<>();
        }
        return node.getAllDescendants();
    }

    public Set<T> getAllRelated(T c) {
        ConceptRelationNode<T> node = map.get(c);
        if (node == null) {
            return new HashSet<>();
        }
        Set<T> result = new HashSet<>();
        result.addAll(node.getAllDescendants());
        result.addAll(node.getAllAncestors());
        result.addAll(node.getNodeLabels());
        return result;
    }

    public List<T> topologicalOrder() {
        if (ordered == null) {
            Map<ConceptRelationNode<T>, Integer> parents = new HashMap<>();
            Queue<ConceptRelationNode<T>> empty = new LinkedList<>();
            for (ConceptRelationNode<T> node : new HashSet<>(map.values())) {
                int size = node.getParents().size();
                if (size == 0) {
                    empty.add(node);
                } else {
                    parents.put(node, size);
                }
            }
            List<T> out = new ArrayList<>();
            while (!empty.isEmpty()) {
                ConceptRelationNode<T> node = empty.remove();
                out.addAll(node.getNodeLabels());
                removeNode(node, parents, empty);
            }
            Collections.reverse(out);
            ordered = out;
        }
        return ordered;
    }

    public List<T> topologicalOrder(Collection<T> order) {
        if (order.size() < 2) {
            return new ArrayList<>(order);
        }
        List<T> existing = new ArrayList<>(topologicalOrder().stream().filter(order::contains).toList());
        List<T> out = new ArrayList<>(order.stream().filter(t -> !existing.contains(t)).toList());
        out.addAll(existing);
        return out;
    }

    private void removeNode(ConceptRelationNode<T> node, Map<ConceptRelationNode<T>, Integer> parents, Queue<ConceptRelationNode<T>> empty) {
        for (ConceptRelationNode<T> entry : new HashSet<>(node.getChildren())) {
            Integer count = parents.get(entry);
            if (count <= 1) {
                empty.add(entry);
                parents.remove(entry);
            } else {
                parents.put(entry, count - 1);
            }
        }
    }
}
