///////////////////////////////////////////////////////////////////////////////
// For information as to what this class does, see the Javadoc, below.       //
// Copyright (C) 1998, 1999, 2000, 2001, 2002, 2003, 2004, 2005, 2006,       //
// 2007, 2008, 2009, 2010, 2014, 2015 by Peter Spirtes, Richard Scheines, Joseph   //
// Ramsey, and Clark Glymour.                                                //
//                                                                           //
// This program is free software; you can redistribute it and/or modify      //
// it under the terms of the GNU General Public License as published by      //
// the Free Software Foundation; either version 2 of the License, or         //
// (at your option) any later version.                                       //
//                                                                           //
// This program is distributed in the hope that it will be useful,           //
// but WITHOUT ANY WARRANTY; without even the implied warranty of            //
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the             //
// GNU General Public License for more details.                              //
//                                                                           //
// You should have received a copy of the GNU General Public License         //
// along with this program; if not, write to the Free Software               //
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA //
///////////////////////////////////////////////////////////////////////////////

package edu.cmu.tetrad.search;

import edu.cmu.tetrad.data.IKnowledge;
import edu.cmu.tetrad.graph.*;
import edu.cmu.tetrad.util.ChoiceGenerator;
import edu.cmu.tetrad.util.ForkJoinPoolInstance;
import edu.cmu.tetrad.util.TetradLogger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RecursiveTask;

/**
 * This class provides the data structures and methods for carrying out the Cyclic Causal Discovery algorithm (CCD)
 * described by Thomas Richardson and Peter Spirtes in Chapter 7 of Computation, Causation, & Discovery by Glymour and
 * Cooper eds.  The comments that appear below are keyed to the algorithm specification on pp. 269-271. </p> The search
 * method returns an instance of a Graph but it also constructs two lists of node triples which represent the underlines
 * and dotted underlines that the algorithm discovers.
 *
 * @author Frank C. Wimberly
 * @author Joseph Ramsey
 */
public final class CcdMax implements GraphSearch {
    private IndependenceTest independenceTest;
    private int depth = -1;
    private IKnowledge knowledge;
    private boolean applyR1 = false;

    public CcdMax(IndependenceTest test) {
        if (test == null) throw new NullPointerException();
        this.independenceTest = test;
    }

    //======================================== PUBLIC METHODS ====================================//

    /**
     * The search method assumes that the IndependenceTest provided to the constructor is a conditional independence
     * oracle for the SEM (or Bayes network) which describes the causal structure of the population. The method returns
     * a PAG instantiated as a Tetrad GaSearchGraph which represents the equivalence class of digraphs which are
     * d-separation equivalent to the digraph of the underlying model (SEM or BN). </p> Although they are not returned
     * by the search method it also computes two lists of triples which, respectively store the underlines and dotted
     * underlines of the PAG.
     */
    public Graph search() {

        // Step A
        Graph psi = stepA();

        stepB(psi);
        stepsEF(psi);
//        psi = pickAGraph(psi);

        return psi;
    }

    private Graph pickAGraph(Graph psi) {
        psi = new EdgeListGraphSingleConnections(psi);
        Edge nondirectedEdge = null;

        do {
            nondirectedEdge = null;

            for (Edge edge : psi.getEdges()) {
                if (Edges.isNondirectedEdge(edge)) {
                    nondirectedEdge = edge;
                    break;
                }
            }

            if (nondirectedEdge != null) {
                Node x = nondirectedEdge.getNode1();
                Node y = nondirectedEdge.getNode2();
                psi.removeEdge(nondirectedEdge);
                psi.addDirectedEdge(x, y);
                orientAwayFromArrow(x, y, psi);
            }
        } while (nondirectedEdge != null);

        return psi;
    }

    public IKnowledge getKnowledge() {
        return knowledge;
    }

    public int getDepth() {
        return depth;
    }

    public void setDepth(int depth) {
        this.depth = depth;
    }

    public void setKnowledge(IKnowledge knowledge) {
        if (knowledge == null) {
            throw new NullPointerException();
        }
        this.knowledge = knowledge;
    }

    public long getElapsedTime() {
        return 0;
    }

    public void setApplyR1(boolean applyR1) {
        this.applyR1 = applyR1;
    }

    //======================================== PRIVATE METHODS ====================================//

    private Graph stepA() {
        FasStableConcurrent fas = new FasStableConcurrent(null, independenceTest);
        fas.setKnowledge(getKnowledge());
        fas.setDepth(getDepth());
        fas.setVerbose(false);
        fas.setRecordSepsets(false);
        Graph psi = fas.search();
        psi.reorientAllWith(Endpoint.CIRCLE);
        return psi;
    }

    private void stepB(Graph psi) {
        SepsetsMinScore sepsets = new SepsetsMinScore(psi, independenceTest, -1);
        sepsets.setReturnNullWhenIndep(false);

        final Map<Triple, Double> colliders = new ConcurrentHashMap<>();
        final Map<Triple, Double> noncolliders = new ConcurrentHashMap<>();

        List<Node> nodes = psi.getNodes();

        class Task extends RecursiveTask<Boolean> {
            private final SepsetProducer sepsets;
            private final Map<Triple, Double> colliders;
            private final Map<Triple, Double> noncolliders;
            private final int from;
            private final int to;
            private final int chunk = 100;
            private final List<Node> nodes;
            private final Graph psi;

            private Task(SepsetProducer sepsets, List<Node> nodes, Graph graph,
                         Map<Triple, Double> colliders,
                         Map<Triple, Double> noncolliders, int from, int to) {
                this.sepsets = sepsets;
                this.nodes = nodes;
                this.psi = graph;
                this.from = from;
                this.to = to;
                this.colliders = colliders;
                this.noncolliders = noncolliders;
            }

            @Override
            protected Boolean compute() {
                if (to - from <= chunk) {
                    for (int i = from; i < to; i++) {
                        doNodeCollider(sepsets, psi, colliders, noncolliders, nodes.get(i));
                    }

                    return true;
                } else {
                    int mid = (to + from) / 2;

                    Task left = new Task(sepsets, nodes, psi, colliders, noncolliders, from, mid);
                    Task right = new Task(sepsets, nodes, psi, colliders, noncolliders, mid, to);

                    left.fork();
                    right.compute();
                    left.join();

                    return true;
                }
            }
        }

        Task task = new Task(sepsets, nodes, psi, colliders, noncolliders, 0, nodes.size());

        ForkJoinPoolInstance.getInstance().getPool().invoke(task);

        List<Triple> collidersList = new ArrayList<>(colliders.keySet());
        List<Triple> noncollidersList = new ArrayList<>(noncolliders.keySet());

        // Most independent ones first.
        Collections.sort(collidersList, new Comparator<Triple>() {

            @Override
            public int compare(Triple o1, Triple o2) {
                return Double.compare(colliders.get(o2), colliders.get(o1));
            }
        });

        for (Triple triple : collidersList) {
            Node a = triple.getX();
            Node b = triple.getY();
            Node c = triple.getZ();

            if (!(psi.getEndpoint(b, a) == Endpoint.ARROW || psi.getEndpoint(b, c) == Endpoint.ARROW)) {
                psi.removeEdge(a, b);
                psi.removeEdge(c, b);
                psi.addDirectedEdge(a, b);
                psi.addDirectedEdge(c, b);
            }
        }

        for (Triple triple : noncollidersList) {
            Node a = triple.getX();
            Node b = triple.getY();
            Node c = triple.getZ();

            psi.addUnderlineTriple(a, b, c);
        }

        orientAwayFromArrow(psi);
    }

    private void doNodeCollider(SepsetProducer sepsets, Graph psi, Map<Triple, Double> colliders,
                                Map<Triple, Double> noncolliders, Node b) {
        List<Node> adjacentNodes = psi.getAdjacentNodes(b);

        if (adjacentNodes.size() < 2) {
            return;
        }

        ChoiceGenerator cg = new ChoiceGenerator(adjacentNodes.size(), 2);
        int[] combination;

        while ((combination = cg.next()) != null) {
            Node a = adjacentNodes.get(combination[0]);
            Node c = adjacentNodes.get(combination[1]);

            // Skip triples that are shielded.
            if (psi.isAdjacentTo(a, c)) {
                continue;
            }

            List<Node> S = sepsets.getSepset(a, c);
            double score = sepsets.getScore();

            if (S == null) continue;

            if (S.contains(b)) {
                noncolliders.put(new Triple(a, b, c), score);
            } else {
                colliders.put(new Triple(a, b, c), score);
            }
        }
    }

    // Orient feedback loops and a few extra directed edges.
    private void stepsEF(Graph psi) {
        TetradLogger.getInstance().log("info", "\nStep E");

        for (Node x : psi.getNodes()) {
            List<Node> adj = psi.getAdjacentNodes(x);

            for (int i = 0; i < adj.size(); i++) {
                Node a = adj.get(i);

                for (int j = i + 1; j < adj.size(); j++) {
                    Node c = adj.get(j);

                    if (a == c) continue;

                    if (!psi.isDefCollider(a, x, c)) {
                        continue;
                    }

                    for (Node y : adj) {
                        if (y == a || y == c) continue;

                        Edge edge = psi.getEdge(x, y);

                        if (!(Edges.isUndirectedEdge(edge) || Edges.isDirectedEdge(edge))) {
                            continue;
                        }

                        boolean sup1 = supIndep(a, c, x, y, psi);

                        if (psi.isDefCollider(a, y, c)) {
                            boolean sup2 = supIndep(c, a, x, y, psi);

                            if (sup1 && sup2) {
                                psi.removeEdge(x, y);
                                psi.addUndirectedEdge(x, y);
                                psi.addDottedUnderlineTriple(a, x, c);
                                psi.addDottedUnderlineTriple(a, y, c);
                            } else if (sup1) {
                                if (!wouldCreateBadCollider(x, y, psi)) {
                                    psi.removeEdge(x, y);
                                    psi.addDirectedEdge(x, y);
                                    orientAwayFromArrow(x, y, psi);
                                    psi.addDottedUnderlineTriple(a, x, c);
                                }
                            } else if (sup2) {
                                if (!wouldCreateBadCollider(y, x, psi)) {
                                    psi.removeEdge(y, x);
                                    psi.addDirectedEdge(y, x);
                                    orientAwayFromArrow(y, x, psi);
                                    psi.addDottedUnderlineTriple(a, y, c);
                                }
                            }
                        } else if (sup1 && !(psi.isAdjacentTo(a, y) && psi.isAdjacentTo(c, y))) {
                            if (!wouldCreateBadCollider(x, y, psi)) {
                                psi.removeEdge(x, y);
                                psi.addDirectedEdge(x, y);
                                orientAwayFromArrow(x, y, psi);
                            }
                        }
                    }
                }
            }
        }
    }

    // Returns a set that would be a Markov blanket if there were no feedback
    // loops.
    private List<Node> local(Graph psi, Node x) {
        Set<Node> nodes = new HashSet<>(psi.getAdjacentNodes(x));

        for (Node y : new HashSet<>(nodes)) {
            for (Node z : psi.getAdjacentNodes(y)) {
                if (psi.isDefCollider(x, y, z)) {
                    if (z != x) {
                        nodes.add(z);
                    }
                }
            }
        }

        return new ArrayList<>(nodes);
    }

    private void orientAwayFromArrow(Graph graph) {
        for (Edge edge : graph.getEdges()) {
            Node n1 = edge.getNode1();
            Node n2 = edge.getNode2();

            edge = graph.getEdge(n1, n2);

            if (edge.pointsTowards(n1)) {
                orientAwayFromArrow(n2, n1, graph);
            } else if (edge.pointsTowards(n2)) {
                orientAwayFromArrow(n1, n2, graph);
            }
        }
    }

    private void orientAwayFromArrow(Node a, Node b, Graph graph) {
        if (!isApplyR1()) return;

        for (Node c : graph.getAdjacentNodes(b)) {
            if (c == a) continue;
            orientAwayFromArrowVisit(a, b, c, graph);
        }
    }

    private boolean orientAwayFromArrowVisit(Node a, Node b, Node c, Graph graph) {
        if (!Edges.isNondirectedEdge(graph.getEdge(b, c))) {
            return false;
        }

        if (!(graph.isUnderlineTriple(a, b, c))) {
            return false;
        }

        if (graph.getEdge(b, c).pointsTowards(b)) {
            return false;
        }

        graph.removeEdge(b, c);
        graph.addDirectedEdge(b, c);

        for (Node d : graph.getAdjacentNodes(c)) {
            if (d == b) return true;

            Edge bc = graph.getEdge(b, c);

            if (!orientAwayFromArrowVisit(b, c, d, graph)) {
                graph.removeEdge(b, c);
                graph.addEdge(bc);
            }
        }

        return true;
    }

    private boolean wouldCreateBadCollider(Node x, Node y, Graph psi) {
        for (Node z : psi.getAdjacentNodes(y)) {
            if (x == z) continue;
            if (psi.getEndpoint(x, y) != Endpoint.ARROW && psi.getEndpoint(z, y) == Endpoint.ARROW) return true;
        }

        return false;
    }

    private boolean isApplyR1() {
        return applyR1;
    }

    private boolean supIndep(Node a, Node c, Node x, Node y, Graph psi) {
        Set<Node> cond = new HashSet<>();
        cond.add(x);
        cond.add(y);
        cond.addAll(local(psi, a));
        cond.remove(c); // a will automatically not be included. c will be.
        return independenceTest.isIndependent(a, c, new ArrayList<>(cond));
    }
}





