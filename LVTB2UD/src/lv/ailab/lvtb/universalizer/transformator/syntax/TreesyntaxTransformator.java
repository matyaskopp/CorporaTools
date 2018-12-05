package lv.ailab.lvtb.universalizer.transformator.syntax;

import lv.ailab.lvtb.universalizer.pml.PmlANode;
import lv.ailab.lvtb.universalizer.transformator.morpho.XPosLogic;
import lv.ailab.lvtb.universalizer.utils.Logger;
import lv.ailab.lvtb.universalizer.transformator.Sentence;
import lv.ailab.lvtb.universalizer.transformator.TransformationParams;

import java.util.List;

/**
 * This is the part of the transformation where base UD tree is made. This part
 * is also responsible for creating ellipsis tokens for ehnahnced dependencies.
 * This class creates creates ellipsis-related enhanced dependency links and
 * copies those dependency links, which are the same for both base UD and
 * enhanced. Thus, the part of enhanced dependency graph made by this class is
 * a backbone tree connecting all nodes.
 */
public class TreesyntaxTransformator
{
	/**
	 * In this sentence all the transformations are carried out.
	 */
	public Sentence s;

	protected TransformationParams params;
	protected PhraseTransformator pTransf;
	// TODO - do not duplicate for GraphsyntaxTransformator and TreesyntaxTransformator?
	protected DepRelLogic dpTransf;
	/**
	 * Stream for warnings.
	 */
	protected Logger logger;

	public TreesyntaxTransformator(Sentence sent, TransformationParams params,
								   Logger logger)
	{
		s = sent;
		this.logger = logger;
		this.params = params;
		pTransf = new PhraseTransformator(s, logger);
		dpTransf = new DepRelLogic(logger);
	}

	/**
	 * Fill in DEPREL and HEAD fields in CoNLL-U table.
	 */
	public void transformBaseSyntax()
	{
		PmlANode pmlPmc = s.pmlTree.getPhraseNode();
		if (pmlPmc == null || pmlPmc.getNodeType() != PmlANode.Type.PMC)
			throw new IllegalArgumentException(String.format(
					"Sentence %s has no root PMC.", s.id));

		transformDepSubtrees(s.pmlTree);
		transformPhraseParts(pmlPmc);

		PmlANode newRoot = pTransf.anyPhraseToUD(pmlPmc);
		if (newRoot == null)
			throw new IllegalArgumentException(String.format(
					"Sentence %s has untransformable root PMC.", s.id));
		s.pmlaToConll.put(s.pmlTree.getId(), s.pmlaToConll.get(newRoot.getId()));
		if (s.pmlaToEnhConll.containsKey(newRoot.getId()))
			s.pmlaToEnhConll.put(s.pmlTree.getId(), s.pmlaToEnhConll.get(newRoot.getId()));
		s.setRoot(newRoot, true);
		relinkDependents(s.pmlTree, newRoot, newRoot);
	}

	/**
	 * Helper method: find all dependency children and process subtrees they are
	 * heads of.
	 * @param parentANode	node whose dependency children will be processed

	 */
	protected void transformDepSubtrees(PmlANode parentANode)
	{
		List<PmlANode> pmlDependents = parentANode.getChildren();
		if (pmlDependents == null || pmlDependents.isEmpty()) return;
		for (PmlANode pmlDependent : pmlDependents)
			transformSubtree(pmlDependent);
	}

	/**
	 * Helper method: process subtrees under each part of PML phrase.
	 * @param phraseInfoNode	node whose dependency children will be processed
	 */
	protected void transformPhraseParts(PmlANode phraseInfoNode)
	{
		List<PmlANode> parts = phraseInfoNode.getChildren();
		if (parts == null || parts.isEmpty()) return;
		for (PmlANode part : parts)
			transformSubtree(part);
	}

	/**
	 * Helper method: fill in DEPREL and HEAD fields in CoNLL-U table for given
	 * subtree.
	 * @param aNode	root of the subtree to process
	 */
	protected void transformSubtree (PmlANode aNode)
	{
		if (params.DEBUG)
			System.out.printf("Working on node \"%s\".\n", aNode.getId());

		List<PmlANode> children = aNode.getChildren();
		PmlANode phraseNode = aNode.getPhraseNode();
		if (phraseNode == null && (children == null || children.size() < 1))
			return;

		transformDepSubtrees(aNode);

		PmlANode newBasicRoot = aNode;
		PmlANode newEnhancedRoot = aNode;
		// Valid LVTB PMLs have no more than one type of phrase - pmc, x or coord.

		//// Process phrase overlords.
		if (phraseNode != null)
		{
			transformPhraseParts(phraseNode);
			newBasicRoot = pTransf.anyPhraseToUD(phraseNode);
			newEnhancedRoot = newBasicRoot;
			if (newBasicRoot == null)
				throw new IllegalStateException(
						"Algorithmic error: phrase transformation returned \"null\" root in sentence " + s.id);

			if (params.INDUCE_PHRASE_TAGS)
			{
				String phraseTag = aNode.getAnyTag();
				String newRootTag = newBasicRoot.getAnyTag();
				if ((phraseTag == null || phraseTag.length() < 1 || phraseTag.matches("N/[Aa]")) &&
						newRootTag != null && newRootTag.length() > 0)
				{
					PmlANode.Type type = phraseNode.getNodeType();
					if (type == PmlANode.Type.X || type == PmlANode.Type.COORD)
						phraseNode.setPhraseTag(newRootTag + "[INDUCED]");
				}
			}
		}
		//// Process reduction nodes.
		else if (aNode.isPureReductionNode())
		{
			String nodeId = aNode.getId();
			PmlANode redRoot = EllipsisLogic.newParent(aNode, dpTransf, logger);
			if (redRoot == null)
				throw new IllegalArgumentException(String.format(
						"No child was raised for ellipsis node %s.", nodeId));
			String redXPostag = XPosLogic.getXpostag(aNode.getReductionTagPart());
			newBasicRoot = redRoot;

			// Make new token for ellipsis.
			// TODO more precise restriction?
			if (redXPostag.matches("v..([^p].*|p[du].*)") || ! params.UD_STANDARD_NULLNODES)
				s.createNewEnhEllipsisNode(aNode, newBasicRoot.getId(), params.ADD_NODE_IDS, logger);


			transformSubtree(newBasicRoot);
		}

		//// Add information about new subroot in the result structure.
		s.pmlaToConll.put(aNode.getId(), s.pmlaToConll.get(newBasicRoot.getId()));
		if (s.pmlaToEnhConll.containsKey(newEnhancedRoot.getId()))
			s.pmlaToEnhConll.put(aNode.getId(), s.pmlaToEnhConll.get(newEnhancedRoot.getId()));

		//// Process dependants (except the newRoot).
		relinkDependents(aNode, newBasicRoot, newEnhancedRoot);
	}


	/**
	 * Helper method: fill in DEPREL and HEAD fields in CoNLL-U table for PML
	 * dependency children of the given node. If the newRoot is one of the
	 * dependents, then it must be processed before invoking this method.
	 * To use this function, previous should have set that conllu tokens who
	 * correspond old and new parent are the same.
	 * @param parentANode		node whose dependency children will be processed
	 * @param newBaseDepRoot	node that will be the root of the coresponding
	 *                  		base UD structure
	 * @param newEnhDepRoot		node that will be the root of the coresponding
	 *                  		enhanced UD structure
	 */
	protected void relinkDependents(
			PmlANode parentANode, PmlANode newBaseDepRoot, PmlANode newEnhDepRoot)
	{
		if (newEnhDepRoot == null) newEnhDepRoot = newBaseDepRoot;

		// To use this function, previous should have set that conllu tokens
		// who correspond old and new parent are the same.
		if (s.pmlaToConll.get(newBaseDepRoot.getId()) != s.pmlaToConll.get(parentANode.getId()) ||
				!s.getEnhancedOrBaseToken(newEnhDepRoot).equals(s.getEnhancedOrBaseToken(parentANode)))
			throw new IllegalArgumentException(String.format(
					"Can't relink dependents from %s to %s!",
					parentANode.getId(), newBaseDepRoot.getId()));

		List<PmlANode> pmlDependents = parentANode.getChildren();
		if (pmlDependents != null && pmlDependents.size() > 0)
			for (PmlANode pmlDependent : pmlDependents)
			{
				s.setBaseLink(newBaseDepRoot, pmlDependent,
						dpTransf.depToUDBase(pmlDependent));
				s.setEnhLink(newEnhDepRoot, pmlDependent,
						dpTransf.depToUDEnhanced(pmlDependent),
						true, true);
			}
	}
}
