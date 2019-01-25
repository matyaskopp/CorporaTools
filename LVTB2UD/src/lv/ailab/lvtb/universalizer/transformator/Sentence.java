package lv.ailab.lvtb.universalizer.transformator;

import lv.ailab.lvtb.universalizer.conllu.EnhencedDep;
import lv.ailab.lvtb.universalizer.conllu.MiscKeys;
import lv.ailab.lvtb.universalizer.conllu.Token;
import lv.ailab.lvtb.universalizer.conllu.UDv2Relations;
import lv.ailab.lvtb.universalizer.pml.LvtbRoles;
import lv.ailab.lvtb.universalizer.pml.LvtbXTypes;
import lv.ailab.lvtb.universalizer.pml.PmlANode;
import lv.ailab.lvtb.universalizer.pml.utils.PmlANodeListUtils;
import lv.ailab.lvtb.universalizer.transformator.morpho.*;
import lv.ailab.lvtb.universalizer.transformator.syntax.DepRelLogic;
import lv.ailab.lvtb.universalizer.transformator.syntax.PhrasePartDepLogic;
import lv.ailab.lvtb.universalizer.utils.Tuple;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Sentence data and some generic often used UD subtree construction routines.
 * Created on 2016-04-20.
 *
 * @author Lauma
 */
public class Sentence
{
	/**
	 * Sentence ID.
	 */
	public String id;
	/**
	 * Original text.
	 */
	public String text;
	/**
	 * LVTB PML representation of the tree.
	 */
	public PmlANode pmlTree;
	/**
	 * UD dependency tree as and conll-style array.
	 */
	public ArrayList<Token> conll = new ArrayList<>();
	/**
	 * Mapping from A-level ids to CoNLL tokens.
	 * Here goes phrase representing empty nodes, if it has been resolved, which
	 * child will be the parent of the dependency subtree.
	 */
	public HashMap<String, Token> pmlaToConll = new HashMap<>();
	/**
	 * Additional mapping for enhanced dependencies. Only updated, when mapping
	 * is different from pmlaToConll.
	 */
	public HashMap<String, Token> pmlaToEnhConll = new HashMap<>();

	/**
	 * Mapping from node ID to IDs of coordinated parts that are direct or
	 * indirect part of this node. After populating this structure, each set
	 * contains at least node itself.
	 */
	public HashMap<String, HashSet<String>> coordPartsUnder = new HashMap<>();

	/**
	 * Mapping from PML phrase node ID to constituent node ID that is root foor
	 * corresponding dependency subtree (head constituent). Populated during
	 * transformation.
	 */
	public HashMap<String, String> phraseHeadConstituents = new HashMap<>();

	/**
	 * Mapping (multimap) between controled and rised subjects and nodes they
	 * should be linked to. Indexed by subject node IDs. Does not include
	 * standard subject links, i.e. cases when subject is directly dependent
	 * from the node. This structure is updated also during transformation.
	 */
	public HashMap<String, HashSet<String>> subj2gov = new HashMap<>();

	public Sentence(PmlANode pmlTree)
	{
		this.pmlTree = pmlTree;
		id = pmlTree.getId();
	}

	public String toConllU()
	{
		StringBuilder res = new StringBuilder();
		res.append("# sent_id = ");
		res.append(id);
		res.append("\n");
		res.append("# text = ");
		res.append(text);
		res.append("\n");
		for (Token t : conll)
			res.append(t.toConllU());
		res.append("\n");
		return res.toString();
	}

	// ===== Pre-transformation preparations. ==================================

	/**
	 * This populates subj2gov map by collecting controlled subjects that are
	 * not direct dependents of predicate. Also links to auxVerb labeled xPred
	 * parts with true auxiliary verb lemma not collected.
	 */
	public void populateSubjectMap()
	{
		HashMap<String, HashSet<String>> gov2subj = new HashMap<>();
		gov2subj = getGov2subj(pmlTree, gov2subj);
		for (String node : gov2subj.keySet()) for (String subj : gov2subj.get(node))
		{
			HashSet<String> tmp = subj2gov.get(subj);
			if (tmp == null) tmp = new HashSet<>();
			if (!node.equals(pmlTree.getDescendant(subj).getParent().getId()))
				tmp.add(node);
			if (!tmp.isEmpty()) subj2gov.put(subj, tmp);
		}
	}

	/**
	 * This collects everything like controlled or direct subject, excluding
	 * links to auxVerb labeled xPred parts with true auxiliary verb lemma.
	 * @param aNode				node whose subtree should be surveyed
	 * @param resultAccumulator	result accumulator for recursive function
	 * @return	multimapping from potential subject parent node IDs to subject
	 * 			node IDs.
	 */
	protected HashMap<String, HashSet<String>> getGov2subj(PmlANode aNode, HashMap<String, HashSet<String>> resultAccumulator)
	{
		if (aNode == null) return null;
		if (resultAccumulator == null) resultAccumulator = new HashMap<>();
		String id = aNode.getId();

		// Update coresponding subject list with dependant subjects.
		HashSet<String> collectedSubjs = resultAccumulator.get(id);
		if (collectedSubjs == null) collectedSubjs = new HashSet<>();
		List<PmlANode> subjs = aNode.getChildren(LvtbRoles.SUBJ);
		if (subjs != null) for (PmlANode s : subjs)
			collectedSubjs.add(s.getId());
		if (!collectedSubjs.isEmpty()) resultAccumulator.put(id, collectedSubjs);

		// Find xPred and update their parts' subject lists.
		PmlANode phrase = aNode.getPhraseNode();
		List<PmlANode> phraseParts = null;
		if (phrase != null)
		{
			phraseParts = phrase.getChildren();
			if (LvtbXTypes.XPRED.equals(phrase.getPhraseType()))
				for (PmlANode phrasePart : phraseParts)
			{
				// Do not add standard auxiliaries used as aux.
				String partRole = phrasePart.getRole();
				if (LvtbRoles.AUXVERB.equals(partRole)
						&& ((phrasePart.getM() != null && MorphoTransformator.isTrueAux(phrasePart.getM().getLemma()))
							|| MorphoTransformator.isTrueAux(phrasePart.getReductionLemma())))
					continue;

				String partId = phrasePart.getId();
				HashSet<String> collectedPartSubjs = resultAccumulator.get(partId);
				if (collectedPartSubjs == null)
					collectedPartSubjs = new HashSet<>();
				collectedPartSubjs.addAll(collectedSubjs);
				if (!collectedPartSubjs.isEmpty()) resultAccumulator.put(partId, collectedPartSubjs);
			}
		}

		// Posprocess children.
		List<PmlANode> dependants = aNode.getChildren();
		if (dependants != null) for (PmlANode dependant : dependants)
			getGov2subj(dependant, resultAccumulator);
		if (phraseParts != null) for (PmlANode phrasePart : phraseParts)
			getGov2subj(phrasePart, resultAccumulator);
		return resultAccumulator;
	}

	public void populateCoordPartsUnder()
	{
		coordPartsUnder = new HashMap<>();
		populateCoordPartsUnder(pmlTree);
		/*System.out.println(
			coordPartsUnder.keySet().stream().sorted()
				.filter(k -> coordPartsUnder.containsKey(k))
				.filter(k -> coordPartsUnder.get(k) != null)
				.map(k -> k + " -> " + coordPartsUnder.get(k).stream().sorted().reduce((a, b) -> a + ", " + b).orElse("NULL"))
				.reduce((k1, k2) -> k1 + System.lineSeparator() + k2).orElse("NULL"));
				//*/
	}

	protected void populateCoordPartsUnder(PmlANode aNode)
	{
		if (aNode == null) return;
		String id = aNode.getId();
		HashSet<String> eqs = new HashSet<String>(){{add(id);}};

		// Preprocess dependency children.
		List<PmlANode> dependants = aNode.getChildren();
		if (dependants != null) for (PmlANode dependant : dependants)
			populateCoordPartsUnder(dependant);

		PmlANode phrase = aNode.getPhraseNode();
		if (phrase != null)
		{
			// Preprocess phrase parts.
			List<PmlANode> phraseParts = phrase.getChildren();
			if (phraseParts != null) for (PmlANode phrasePart : phraseParts)
				populateCoordPartsUnder(phrasePart);

			// Add coordinated subparts for this node.
			if (phrase.getNodeType() == PmlANode.Type.COORD)
			{
				List<PmlANode> importantParts = phrase.getChildren(LvtbRoles.CRDPART);
				if (importantParts != null) for (PmlANode phrasePart : importantParts)
				{
					String partId = phrasePart.getId();
					if (coordPartsUnder.containsKey(partId))
						eqs.addAll(coordPartsUnder.get(partId));
				}
			}
		}
		coordPartsUnder.put(id, eqs);
	}


	// ===== After-transformation clean-ups. ===================================

	@Deprecated // Properly constructed algorithm should not make those.
	public void removeDuplicateDeps()
	{
		for (Token t : conll)
		{
			HashSet<String> goodEnhDepHeads = new HashSet<>(t.deps.stream()
					.filter(d -> (d.role != UDv2Relations.DEP))
					.map(d -> d.headID).collect(Collectors.toSet()));
			HashSet<EnhencedDep> noRoleEnhDepsToDelete = new HashSet<>(t.deps.stream()
					.filter(d -> (d.role == UDv2Relations.DEP && goodEnhDepHeads.contains(d.headID)
							&& (d.rolePostfix == null || d.rolePostfix.isEmpty())))
					.collect(Collectors.toSet()));
			t.deps.removeAll(noRoleEnhDepsToDelete);
		}
	}

	// ===== Getters and elaborated getters. ===================================

	/**
	 * Get the enhanced token assigned for this node. If there is no enhanced
	 * token assigned, return assigned base token.
	 * @param aNode	node whose token must be found
	 * @return	enhanced token or base token, or null (in that order)
	 */
	public Token getEnhancedOrBaseToken(PmlANode aNode)
	{
		if (aNode == null) return null;
		String id = aNode.getId();
		if (id == null) return null;
		Token resToken = pmlaToEnhConll.get(id);
		if (resToken == null) resToken = pmlaToConll.get(id);
		return resToken;
	}

	/**
	 * For a given node either return IDs of the coordinated parts this node
	 * represents (if this node is coordination) or node's ID otherwise. In case
	 * a part is a coordination itself, its coordinated parts are included in
	 * the result instead of part itself.
	 * @param aNodeId	Id of node whose coordination parts are needed
	 * @return	IDs of coordinated parts or node itself
	 */
	public HashSet<String> getCoordPartsUnderOrNode (String aNodeId)
	{
		if (aNodeId == null) return null;
		HashSet<String> res = new HashSet<>();
		if (coordPartsUnder.containsKey(aNodeId))
			res.addAll(coordPartsUnder.get(aNodeId));
		else res.add(aNodeId);
		return res;
	}

	/**
	 * For a given node either return IDs of the coordinated parts this node
	 * represents (if this node is coordination) or node's ID otherwise. In case
	 * a part is a coordination itself, its coordinated parts are included in
	 * the result instead of part itself.
	 * @param aNode	node whose coordination parts are needed
	 * @return	IDs of coordinated parts or node itself
	 */
	public HashSet<String> getCoordPartsUnderOrNode (PmlANode aNode)
	{
		return getCoordPartsUnderOrNode(aNode.getId());
	}

	/**
	 * For a certain node ID find its head constituent list and then find all
	 * coordinated parts for each node in that list. Iterate this process
	 * until nothing new can be found.
	 * @param aNodeId				node ID to indicate node
	 * @param includeSelfCoordParts	should the result include coordPartsUnder
	 *                              for given node?
	 * @return
	 */
	public HashSet<String> getAllAlternatives(String aNodeId, boolean includeSelfCoordParts)
	{
		if (aNodeId == null) return null;
		HashSet<String> result = getCoordPartsUnderHeadConstits1Lvl(aNodeId, includeSelfCoordParts);
		if (!includeSelfCoordParts)
			result.remove(aNodeId);
		int oldSize = 1;
		while (oldSize < result.size())
		{
			oldSize = result.size();
			HashSet<String> newResult = new HashSet<>();
			for (String tmpId : result)
				newResult.addAll(getCoordPartsUnderHeadConstits1Lvl(tmpId, true));
			result = newResult;
		}
		result.add(aNodeId);
		return result;
	}

	/**
	 * For a certain node ID find its head constituent list and then find all
	 * coordinated parts for each node in that list. Do not include coordinated
	 * analogues of the given node.
	 * @param aNodeId				node ID to indicate node
	 * @param includeSelfCoordParts	should the result include coordPartsUnder
	 *                              for given node?
	 * @return
	 */
	protected HashSet<String> getCoordPartsUnderHeadConstits1Lvl(
			String aNodeId, boolean includeSelfCoordParts)
	{
		if (aNodeId == null) return null;
		ArrayList<String> headConstituentList = this.getHeadConstituentList(aNodeId);
		HashSet<String> result = new HashSet<>();
		result.add(aNodeId);
		if (!includeSelfCoordParts) headConstituentList.remove(aNodeId);
		for (String headConstituent : headConstituentList)
			result.addAll(getCoordPartsUnderOrNode(headConstituent));
		return result;
	}

	/**
	 * For a certain node ID find if it is a phrase with known head constituent.
	 * If it is, repeat the same with the found constituent node. Return a list
	 * with the first given node and then all head constituents further found.
	 * @param aNodeId	node whose head constituent (or constituent list) should
	 *                  be found
	 * @return	null, if argument is null; other wise list with given node, its
	 * 			head constituent, its head constituent etc...
	 */
	public ArrayList<String> getHeadConstituentList(String aNodeId)
	{
		if (aNodeId == null) return null;
		ArrayList<String> result = new ArrayList<>();
		result.add(aNodeId);
		while (phraseHeadConstituents.containsKey(aNodeId))
		{
			aNodeId = phraseHeadConstituents.get(aNodeId);
			result.add(aNodeId);
		}
		return result;
	}

	// ===== Making new nodes. =================================================

	/**
	 * Create "empty" token for ellipsis.
	 * @param aNode			PML A node, for which respective ellipsis token must
	 *                      be made.
	 * @param baseTokenId	ID for token after which the new token must be
	 *                      inserted.
	 * @param addNodeId		should the ID of the aNode be added to the MISC
	 *                      field of the new token?
	 */
	public void createNewEnhEllipsisNode(
			PmlANode aNode, String baseTokenId, boolean addNodeId)
	{
		String nodeId = aNode.getId();
		String redXPostag = XPosLogic.getXpostag(aNode.getReductionTagPart());

		// Decimal token (reduction node) must be inserted after newRootToken.
		Token newRootToken = pmlaToConll.get(baseTokenId);
		int position = conll.indexOf(newRootToken) + 1;
		while (position < conll.size() && newRootToken.idBegin == conll.get(position).idBegin)
			position++;

		// Fill the fields for the new token.
		Token decimalToken = new Token();
		decimalToken.idBegin = newRootToken.idBegin;
		decimalToken.idSub = conll.get(position-1).idSub+1;
		decimalToken.idEnd = decimalToken.idBegin;
		decimalToken.xpostag = redXPostag;
		decimalToken.form = aNode.getReductionFormPart();
		if (decimalToken.xpostag == null || decimalToken.xpostag.isEmpty() || decimalToken.xpostag.equals("_"))
			StandardLogger.l.doInsentenceWarning(String.format(
					"Ellipsis node %s with reduction field \"%s\" has no tag.",
					nodeId, aNode.getReduction()));
		else
		{
			String assumedLvtbLemma = null;
			if (decimalToken.form != null && !decimalToken.form.isEmpty())
				assumedLvtbLemma = AnalyzerWrapper.getLemma(
						decimalToken.form, decimalToken.xpostag);
			decimalToken.upostag = UPosLogic.getUPosTag(decimalToken.form,
					assumedLvtbLemma, decimalToken.xpostag);
			decimalToken.feats = FeatsLogic.getUFeats(decimalToken.form,
					assumedLvtbLemma, decimalToken.xpostag);
			decimalToken.lemma = LemmaLogic.getULemma(assumedLvtbLemma, redXPostag);
		}
		if (addNodeId && nodeId != null && !nodeId.isEmpty())
		{
			decimalToken.addMisc(MiscKeys.LVTB_NODE_ID, nodeId);
			StandardLogger.l.addIdMapping(id, decimalToken.getFirstColumn(), nodeId);
		}

		// Add the new token to the sentence data structures.
		conll.add(position, decimalToken);
		pmlaToEnhConll.put(nodeId, decimalToken);
	}


	// ===== Simple link setting. ==============================================

	/**
	 * Set both base and enhanced dependency links for tokens coressponding to
	 * the given PML nodes, but do not set circular dependencies. It is expected
	 * that pmlaToEnhConll (if needed) and pmlaToConll contains links from given
	 * PML nodes's IDs to corresponding tokens.
	 * Return silently, if there was no token for given child node. Fail, if
	 * there was no token for given parent node. This asymmetry is done because
	 * inserted nodes have no corresponding UD token, and childless nodes should
	 * just be ignored, while missing node in the middle of the tree, is a major
	 * error.
	 * TODO Maybe we should keep ignore-node list and check agains that?
	 * @param parent 		PML node describing parent
	 * @param child			PML node describing child
	 * @param baseDep		label to be used for base dependency
	 * @param enhancedDep	label to be used for enhanced dependency
	 * @param setBackbone	if enhanced dependency is made, should it be set as
	 *                      backbone for child node
	 * @param cleanOldDeps	whether previous contents from deps field should be
	 *                      removed
	 */
	public void setLink (PmlANode parent, PmlANode child, UDv2Relations baseDep,
						 Tuple<UDv2Relations, String> enhancedDep,
						 boolean setBackbone, boolean cleanOldDeps)
	{
		Token rootBaseToken = pmlaToConll.get(parent.getId());
		Token rootEnhToken = pmlaToEnhConll.get(parent.getId());
		if (rootEnhToken == null) rootEnhToken = rootBaseToken;
		Token childBaseToken = pmlaToConll.get(child.getId());
		Token childEnhToken = pmlaToEnhConll.get(child.getId());
		if (childEnhToken == null) childEnhToken = childBaseToken;
		if (childBaseToken == null) return;

		// Set base dependency, but avoid circular dependencies.
		if (!rootBaseToken.equals(childBaseToken) && childBaseToken.idSub < 1)
		{
			childBaseToken.head = Tuple.of(rootBaseToken.getFirstColumn(), rootBaseToken);
			childBaseToken.deprel = baseDep;
		}

		// Set enhanced dependencies, but avoid circular.
		childEnhToken.setEnhancedHead(rootEnhToken, enhancedDep, setBackbone, cleanOldDeps);
	}

	/**
	 * Set enhanced dependency link for tokens coressponding to the given PML
	 * nodes, but do not set circular dependencies. It is expected that
	 * pmlaToEnhConll (if needed) and pmlaToConll contains links from given
	 * PML nodes's IDs to corresponding tokens.
	 * @param parent 		PML node describing parent
	 * @param child			PML node describing child
	 * @param enhancedDep	label to be used for enhanced dependency
	 * @param setBackbone	if enhanced dependency is made, should it be set as
	 *                      backbone for child node
	 * @param cleanOldDeps	whether previous contents from deps field should be
	 *                      removed
	 */
	public void setEnhLink (PmlANode parent, PmlANode child,
							Tuple<UDv2Relations, String> enhancedDep,
							boolean setBackbone, boolean cleanOldDeps)
	{
		Token rootBaseToken = pmlaToConll.get(parent.getId());
		Token rootEnhToken = pmlaToEnhConll.get(parent.getId());
		if (rootEnhToken == null) rootEnhToken = rootBaseToken;
		Token childBaseToken = pmlaToConll.get(child.getId());
		Token childEnhToken = pmlaToEnhConll.get(child.getId());
		if (childEnhToken == null) childEnhToken = childBaseToken;

		// Set enhanced dependencies, but avoid circular.
		childEnhToken.setEnhancedHead(rootEnhToken, enhancedDep, setBackbone, cleanOldDeps);
	}

	/**
	 * Set basic dependency link for tokens coressponding to the given PML
	 * nodes, but do not set circular dependencies and do not set anything as a
	 * parent to decimal node. It is expected that pmlaToConll contains links
	 * from given PML nodes's IDs to corresponding
	 * tokens.
	 * @param parent 		PML node describing parent
	 * @param child			PML node describing child
	 * @param baseDep	label to be used for enhanced dependency
	 */
	public void setBaseLink (PmlANode parent, PmlANode child, UDv2Relations baseDep)
	{
		Token rootBaseToken = pmlaToConll.get(parent.getId());
		Token childBaseToken = pmlaToConll.get(child.getId());

		// Set base dependency, but avoid circular dependencies.
		if (!rootBaseToken.equals(childBaseToken) && childBaseToken.idSub < 1)
		{
			childBaseToken.head = Tuple.of(rootBaseToken.getFirstColumn(), rootBaseToken);
			childBaseToken.deprel = baseDep;
		}
	}

	/**
	 * setLink() + sets coordination propagation crosslinks from parent's
	 * coordinated equivalents to child's coordinated equivalents. Can be used
	 * only if all crosslinks have the same role as main enhanced dependency
	 * link.
	 * Use for relinking phrasal constituents.
	 * @param parent 		PML node describing parent
	 * @param child			PML node describing child
	 * @param baseDep		label to be used for base dependency
	 * @param enhancedDep	label to be used for enhanced dependency and for
	 *                      crosslinks
	 * @param setBackbone	if enhanced dependency is made, should it be set as
	 *                      backbone for child node
	 * @param cleanOldDeps	whether previous contents from deps field should be
	 *                      removed
	 */
	public void setLinkAndCorsslinksPhrasal(
			PmlANode parent, PmlANode child, UDv2Relations baseDep,
			Tuple<UDv2Relations, String> enhancedDep, boolean setBackbone,
			boolean cleanOldDeps)
	{
		setLink(parent, child, baseDep, enhancedDep, setBackbone, cleanOldDeps);
		addFixedRoleCrosslinks(parent, child, enhancedDep);
	}

	/**
	 * Set both base and enhanced dependency links as root for token(s)
	 * coressponding to the given PML node. It is expecte that pmlaToEnhConll
	 * (if needed) and pmlaToConll contains links from given PML nodes's IDs to
	 * corresponding tokens. This dependency is set as backbone by default.
	 * @param node 			PML node to be made root
	 * @param cleanOldDeps	whether previous contents from deps field should be
	 *                      removed
	 */
	public void setRoot (PmlANode node, boolean cleanOldDeps)
	{
		Token childBaseToken = pmlaToConll.get(node.getId());
		Token childEnhToken = pmlaToEnhConll.get(node.getId());
		if (childEnhToken == null) childEnhToken = childBaseToken;

		// Set base dependency.
		if (childBaseToken.idSub < 1)
		{
			childBaseToken.head = Tuple.of("0", null);
			childBaseToken.deprel = UDv2Relations.ROOT;
		}

		// Set enhanced dependencies.
		childEnhToken.setEnhancedHeadRoot(true, cleanOldDeps);
	}


	// ===== Simple relinking. =================================================

	/*
	 * Changes the heads for all dependencies set (both base and enhanced) for
	 * given childnode. It is expected that pmlaToEnhConll (if needed) and
	 * pmlaToConll contains links from given PML nodes's IDs to corresponding
	 * tokens.
	 * @param newParent	new parent
	 * @param child		child node whose attachment should be changed
	 */
/*	public void changeHead (PmlANode newParent, PmlANode child)
	{
		Token rootBaseToken = pmlaToConll.get(newParent.getId());
		Token rootEnhToken = pmlaToEnhConll.get(newParent.getId());
		if (rootEnhToken == null) rootEnhToken = rootBaseToken;
		Token childBaseToken = pmlaToConll.get(child.getId());
		Token childEnhToken = pmlaToEnhConll.get(child.getId());
		if (childEnhToken == null) childEnhToken = childBaseToken;

		// Set base dependency, but avoid circular dependencies.
		// FIXME is "childBaseToken.head != null" ok?
		if (!rootBaseToken.equals(childBaseToken) && childBaseToken.head != null)
			childBaseToken.head = Tuple.of(rootBaseToken.getFirstColumn(), rootBaseToken);

		// Set enhanced dependencies, but avoid circular.
		if (!childEnhToken.equals(rootEnhToken) && !childEnhToken.deps.isEmpty())
		{
			HashSet<EnhencedDep> newDeps = new HashSet<>();
			for (EnhencedDep ed : childEnhToken.deps)
				newDeps.add(new EnhencedDep(rootEnhToken, ed.role));
			childEnhToken.deps = newDeps;
			if (childEnhToken.depsBackbone != null)childEnhToken.depsBackbone =
					new EnhencedDep(rootEnhToken, childEnhToken.depsBackbone.role);
		}
	}//*/


	// ===== Dependency related linking and relinking. =========================

	/**
	 * Make a list of given nodes UD dependents of the designated parent. Set UD
	 * deprel, deps and deps backbone for each child. If designated parent is
	 * included in child list node, circular dependency is not made, role is not
	 * set.
	 * Use for relinking depdenencies.
	 * @param parent	PML parent node in the hybrid tree (in case of
	 * 	 *              phrase nodes, corresponding tokens must be set correctly)
	 * @param children	list of child nodes
	 * @param addCoordPropCrosslinks    should also coordination propagation be
	 *                                  done?
	 */
	public void relinkAllDependants(
			PmlANode parent, List<PmlANode> children,
			boolean addCoordPropCrosslinks, boolean addControledSubjects)
	{
		if (children == null || children.isEmpty()) return;
		// Process children.
		for (PmlANode child : children)
		{
			relinkSingleDependant(parent, child, addCoordPropCrosslinks);
			if (addControledSubjects)
				addSubjectsControlers(child, addCoordPropCrosslinks);
		}
	}

	/**
	 * Based on previously populated subject map add propagated subjects
	 * for given node.
	 * @param subject		subject node from which controll links sould be made
	 * @param addCoordPropCrosslinks    should also coordination propagation be
	 * 	 *                              done?
	 */
	public void addSubjectsControlers(
			PmlANode subject, boolean addCoordPropCrosslinks)
	{
		if (subject == null) return;
		String subjectId = subject.getId();
		HashSet <String> parentIds = subj2gov.get(subjectId);
		if (parentIds == null || parentIds.isEmpty()) return;

		for (String parentId : parentIds)
		{
			PmlANode parent = pmlTree.getDescendant(parentId);
			Tuple<UDv2Relations, String> enhRole = DepRelLogic.depToUDEnhanced(
					subject, parent, LvtbRoles.SUBJ);
			setEnhLink(parent, subject, enhRole, false, false);
			if (addCoordPropCrosslinks && UDv2Relations.canPropagatePrecheck(enhRole.first))
				addFixedRoleCrosslinks(parent, subject, enhRole);
		}
	}

	/**
	 * Make a given node a child of the designated parent. Set UD role for the
	 * child. Set enhanced dependency and deps backbone. If designated parent is
	 * the same as child node, circular dependency is not made, role is not set.
	 * Use for relinking depdenencies.
	 * @param parent	PML parent node in the hybrid tree (in case of
	 *                  phrase nodes, corresponding tokens must be set correctly)
	 * @param child		designated child
	 * @param addCoordPropCrosslinks	should also coordination propagation be
	 *                                  done?
	 */
	protected void relinkSingleDependant(
			PmlANode parent, PmlANode child, boolean addCoordPropCrosslinks)
	{
		UDv2Relations baseRole = DepRelLogic.depToUDBase(child);
		Tuple<UDv2Relations, String> enhRole = DepRelLogic.depToUDEnhanced(child);
		setBaseLink(parent, child, baseRole);
		setEnhLink(parent, child, enhRole, true, true);
		if (addCoordPropCrosslinks && UDv2Relations.canPropagatePrecheck(enhRole.first))
			addDependencyCrosslinks(parent, child);
	}

	/**
	 * Utility function for dependency transformation. When dependency link
	 * between two nodes is transformed to UD, this can be used to add enhanced
	 * links (conjunct propagation) between nodes that are coordinated with
	 * parent or child.
	 * Uses UDv2Relations.canPropagateAftercheck() to determine if role should
	 * be made.
	 * Use for relinking dependencies.
	 * @param parent		phrase part to become dependency parent
	 * @param child			phrase part to become dependency child
	 */
	protected void addDependencyCrosslinks (PmlANode parent, PmlANode child)
	{
		HashSet<String> altParentKeys = coordPartsUnder.get(parent.getId());
		HashSet<String> altChildKeys = coordPartsUnder.get(child.getId());
		for (String altParentKey : altParentKeys) for (String altChildKey : altChildKeys)
		{
			PmlANode altParent = null;
			if (pmlTree.getId().equals(altParentKey)) altParent = pmlTree;
			else altParent = pmlTree.getDescendant(altParentKey);
			PmlANode altChild = pmlTree.getDescendant(altChildKey);
			// Role for ehnanced link is obtained by actual parent,
			// actual child, but by "place-holder" role obtained
			// from the original child.
			Tuple<UDv2Relations, String> childDeprel =
					DepRelLogic.depToUDEnhanced(altChild, altParent, child.getRole());
			if (UDv2Relations.canPropagateAftercheck(childDeprel.first))
			{
				HashSet<String> altParentKeys2 = getAllAlternatives(altParentKey, false);
				HashSet<String> altChildKeys2 = getAllAlternatives(altChildKey, false);
				for (String altParentKey2 : altParentKeys2) for (String altChildKey2 : altChildKeys2)
				{
					PmlANode altParent2 = null;
					if (pmlTree.getId().equals(altParentKey2))
						altParent2 = pmlTree;
					else altParent2 = pmlTree.getDescendant(altParentKey2);
					PmlANode altChild2 = pmlTree.getDescendant(altChildKey2);
					setEnhLink(altParent2, altChild2, childDeprel, false, false);
				}
			}
		}
	}

	// ===== Constituency related linking & relinking. =========================

	/**
	 * For the given node find first children of the given type and make all
	 * other children depend on it. Set UD deprel and enhanced backbone for each
	 * child.
	 * Use for relinking phrasal constituents.
	 * @param phraseNode		node whose children must be processed, and whose
	 *                          type and tag is used, if needed, to obtain
	 *                          correct UD role for children
	 * @param newRootType		subroot for new UD structure will be searched
	 *                          between PML nodes with this type/role
	 * @param addCoordPropCrosslinks	should also coordination propagation be
	 *                                  done?
	 * @param warnMoreThanOne	whether to warn if more than one potential root
	 *                          is found
	 * @return root of the corresponding dependency structure
	 */
	public PmlANode allUnderFirstConstituent(
			PmlANode phraseNode, String newRootType, boolean addCoordPropCrosslinks,
			boolean warnMoreThanOne)
	{
		List<PmlANode> children = phraseNode.getChildren();
		List<PmlANode> potentialRoots = phraseNode.getChildren(newRootType);
		if (warnMoreThanOne && potentialRoots != null && potentialRoots.size() > 1)
			StandardLogger.l.doInsentenceWarning(String.format(
					"\"%s\" in sentence \"%s\" has more than one \"%s\".",
					phraseNode.getPhraseType(), id, newRootType));
		PmlANode newRoot = PmlANodeListUtils.getFirstByDeepOrd(potentialRoots);
		if (newRoot == null)
		{
			StandardLogger.l.doInsentenceWarning(String.format(
					"\"%s\" in sentence \"%s\" has no \"%s\".",
					phraseNode.getPhraseType(), id, newRootType));
			newRoot = PmlANodeListUtils.getFirstByDeepOrd(children);
		}
		if (newRoot == null)
			throw new IllegalArgumentException(String.format(
					"\"%s\" in sentence \"%s\" seems to be empty.\n",
					phraseNode.getPhraseType(), id));
		relinkAllConstituents(newRoot, children, phraseNode, addCoordPropCrosslinks);
		return newRoot;
	}

	/**
	 * For the given node find first children of the given type and make all
	 * other children depend on it. Set UD deprel and enhanced backbone for each
	 * child.
	 * Use for relinking phrasal constituents.
	 * @param phraseNode        node whose children must be processed, and whose
	 *                          type and tag is used, if needed, to obtain
	 *                          correct UD role for children
	 * @param newRootType		subroot for new UD structure will be searched
	 *                          between PML nodes with this type/role
	 * @param newRootBackUpType backUpRole, if no nodes of newRootType is found
	 * @param addCoordPropCrosslinks	should also coordination propagation be
	 *                                  done?
	 * @param warnMoreThanOne	whether to warn if more than one potential root
	 *                          is found
	 * @return root of the corresponding dependency structure
	 */
	public PmlANode allUnderLastConstituent(
			PmlANode phraseNode, String newRootType, String newRootBackUpType,
			boolean addCoordPropCrosslinks, boolean warnMoreThanOne)
	{
		List<PmlANode> children = phraseNode.getChildren();
		List<PmlANode> potentialRoots = phraseNode.getChildren(newRootType);
		if (newRootBackUpType != null &&
				(potentialRoots == null || potentialRoots.size() < 1))
			potentialRoots = phraseNode.getChildren(newRootBackUpType);
		PmlANode newRoot = PmlANodeListUtils.getLastByDeepOrd(potentialRoots);
		if (warnMoreThanOne && potentialRoots != null && potentialRoots.size() > 1)
			StandardLogger.l.doInsentenceWarning(String.format(
					"\"%s\" in sentence \"%s\" has more than one \"%s\".",
					phraseNode.getPhraseType(), id, newRoot.getAnyLabel()));
		if (newRoot == null)
		{
			StandardLogger.l.doInsentenceWarning(String.format(
					"\"%s\" in sentence \"%s\" has no \"%s\".",
					phraseNode.getPhraseType(), id, newRootType));
			newRoot = PmlANodeListUtils.getLastByDeepOrd(children);
		}
		if (newRoot == null)
			throw new IllegalArgumentException(String.format(
					"\"%s\" in sentence \"%s\" seems to be empty.\n",
					phraseNode.getPhraseType(), id));
		relinkAllConstituents(newRoot, children, phraseNode, addCoordPropCrosslinks);
		return newRoot;
	}

	/**
	 * Make a list of given nodes children of the designated parent. Set UD
	 * deprel, deps and deps backbone for each child. If designated parent is
	 * included in child list node, circular dependency is not made, role is not
	 * set.
	 * Use for relinking phrasal constituents.
	 * @param newRoot		designated parent
	 * @param children		list of child nodes
	 * @param phraseNode    phrase data node from PML data, used for obtaining
	 *                      correct UD role for children
	 * @param addCoordPropCrosslinks    should also coordination propagation be
	 *                                  done?
	 */
	public void relinkAllConstituents(
			PmlANode newRoot, List<PmlANode> children, PmlANode phraseNode,
			boolean addCoordPropCrosslinks)
	{
		if (children == null || children.isEmpty()) return;

		// Process children.
		for (PmlANode child : children)
			relinkSingleConstituent(newRoot, child, phraseNode, addCoordPropCrosslinks);
	}

	/**
	 * Make a given node a child of the designated parent. Set UD role for the
	 * child. Set enhanced dependency and deps backbone. If designated parent is
	 * the same as child node, circular dependency is not made, role is not set.
	 * Use for relinking phrasal constituents.
	 * @param parent		designated parent
	 * @param child			designated child
	 * @param phraseNode    phrase data node from PML data, used for obtaining
	 *                      correct UD role for children
	 * @param addCoordPropCrosslinks	should also coordination propagation be
	 *                                  done?
	 */
	public void relinkSingleConstituent(
			PmlANode parent, PmlANode child, PmlANode phraseNode,
			boolean addCoordPropCrosslinks)
	{
		if (child == null || child.isSameNode(parent)) return;

		Tuple<UDv2Relations, String> childDeprel =
				PhrasePartDepLogic.phrasePartRoleToUD(child, phraseNode);
		setLink(parent, child, childDeprel.first, childDeprel, true,true);
		if (addCoordPropCrosslinks && UDv2Relations.canPropagatePrecheck(childDeprel.first))
			addPhrasalConjunctCrosslinks(parent, child, phraseNode);
	}

	/**
	 * Utility function for phrase transformation. When phrase phrase is
	 * transformed to UD and a dependency link between two constituents is
	 * established, this can be used to add enhanced links (conjunct propagation)
	 * between nodes that are coordinated with parent or child.
	 * Uses UDv2Relations.canPropagateAftercheck() to determine if role should
	 * be made.
	 * Use for relinking phrasal constituents.
	 * @param parent		phrase part to become dependency parent
	 * @param child			phrase part to become dependency child
	 * @param phraseNode	phrase information by which role will be determined
	 */
	protected void addPhrasalConjunctCrosslinks (
			PmlANode parent, PmlANode child, PmlANode phraseNode)
	{
		if (parent == null || child == null) return;
		Tuple<UDv2Relations, String> childDeprel =
				PhrasePartDepLogic.phrasePartRoleToUD(child, phraseNode);
		if (!UDv2Relations.canPropagatePrecheck(childDeprel.first)) return;
		if (!UDv2Relations.canPropagateAftercheck(childDeprel.first)) return;

		HashSet<String> altParentKeys = getAllAlternatives(parent.getId(), true);
		HashSet<String> altChildKeys = getAllAlternatives(child.getId(), true);

		for (String altParentKey : altParentKeys)
			for (String altChildKey : altChildKeys)
			{
				PmlANode altParent = pmlTree.getDescendant(altParentKey);
				PmlANode altChild = pmlTree.getDescendant(altChildKey);
				setEnhLink(altParent, altChild, childDeprel, false, false);
			}
	}

	// ===== Utility for both donstituency and dependency related linking &
	// relinking. ==============================================================

	/**
	 * Utility function for either phrase or dependency transformation - this
	 * can be used to add enhanced links (conjunct propagation) between nodes
	 * that are coordinated with parent or child.
	 * NB. Use this sparingly as this function assumes that all crosslinks have the
	 * same role!
	 * Uses both UDv2Relations.canPropagatePrecheck() and
	 * UDv2Relations.canPropagateAftercheck() to determine if role should be
	 * made.
	 * @param parent		node to become dependency parent
	 * @param child			node to become dependency child
	 * @param childDeprel	fixed role for all crosslinks
	 */
	protected void addFixedRoleCrosslinks(
			PmlANode parent, PmlANode child, Tuple<UDv2Relations, String> childDeprel)
	{
		if (parent == null || child == null) return;
		if (!UDv2Relations.canPropagatePrecheck(childDeprel.first)) return;
		if (!UDv2Relations.canPropagateAftercheck(childDeprel.first)) return;

		HashSet<String> altParentKeys = getAllAlternatives(parent.getId(), true);
		HashSet<String> altChildKeys = getAllAlternatives(child.getId(), true);

		for (String altParentKey : altParentKeys) for (String altChildKey : altChildKeys)
		{
			PmlANode altParent = pmlTree.getDescendant(altParentKey);
			PmlANode altChild = pmlTree.getDescendant(altChildKey);
			setEnhLink(altParent, altChild, childDeprel,false, false);
		}
	}

	// ===== Other. ============================================================

	public boolean removeFromSubjectMap(String subjectId, String governorId)
	{
		if (!subj2gov.containsKey(subjectId)) return false;
		HashSet<String> tmp = subj2gov.get(subjectId);
		if (!tmp.contains(governorId)) return false;
		tmp.remove(governorId);
		if (tmp.isEmpty()) subj2gov.remove(subjectId);
		else subj2gov.put(subjectId, tmp);
		return true;
	}

	public boolean removeGovFromSubjectMap(String governorId)
	{
		int removed = 0;
		for (String subjectId : subj2gov.keySet())
		{
			HashSet<String> tmp = subj2gov.get(subjectId);
			if (!tmp.contains(governorId)) continue;
			tmp.remove(governorId);
			if (tmp.isEmpty()) subj2gov.remove(subjectId);
			else subj2gov.put(subjectId, tmp);
			removed++;
		}
		return removed > 0;
	}
}
