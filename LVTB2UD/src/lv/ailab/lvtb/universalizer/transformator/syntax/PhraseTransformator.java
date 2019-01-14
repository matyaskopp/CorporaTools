package lv.ailab.lvtb.universalizer.transformator.syntax;

import lv.ailab.lvtb.universalizer.conllu.UDv2Relations;
import lv.ailab.lvtb.universalizer.pml.*;
import lv.ailab.lvtb.universalizer.pml.utils.PmlANodeListUtils;
import lv.ailab.lvtb.universalizer.transformator.Sentence;
import lv.ailab.lvtb.universalizer.transformator.StandardLogger;
import lv.ailab.lvtb.universalizer.utils.Tuple;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Logic for creating dependency structures from LVTB phrase-style structures.
 * No change is done in PML tree, all results are stored in CoNLL-U table only.
 * Created on 2016-04-20.
 *
 * @author Lauma
 */
public class PhraseTransformator
{
	/**
	 * In this sentence all the transformations are carried out.
	 */
	public Sentence s;

	public PhraseTransformator(Sentence sent)
	{
		s = sent;
	}
	/**
	 * Transform phrase to the UD structure and updates Sentence.phraseRoots
	 * variable.
	 * @return PML A-level node: root of the corresponding UD structure.
	 */
	public PmlANode anyPhraseToUD(PmlANode phraseNode, boolean addCoordPropCrosslinks)
	{
		PmlANode result = anyPhraseToUDLogic(phraseNode, addCoordPropCrosslinks);
		String phraseId = phraseNode.getParent().getId();
		String resultId = result.getId();
		s.phraseRoots.put(phraseId, resultId);
		return result;
	}
	/**
	 * Transform phrase to the UD structure.
	 * @return PML A-level node: root of the corresponding UD structure.
	 */
	public PmlANode anyPhraseToUDLogic(PmlANode phraseNode, boolean addCoordPropCrosslinks)
	{
		String phraseType = phraseNode.getPhraseType();

		//======= PMC ==========================================================

		if (phraseType.equals(LvtbPmcTypes.SENT) ||
				phraseType.equals(LvtbPmcTypes.DIRSPPMC) ||
				phraseType.equals(LvtbPmcTypes.INSPMC))
			return sentencyToUD(phraseNode, addCoordPropCrosslinks);
		if (phraseType.equals(LvtbPmcTypes.UTTER))
			return utterToUD(phraseNode, addCoordPropCrosslinks);
		if (phraseType.equals(LvtbPmcTypes.SUBRCL) ||
				phraseType.equals(LvtbPmcTypes.MAINCL))
			return s.allUnderFirstConstituent(phraseNode, LvtbRoles.PRED, addCoordPropCrosslinks, true);
		if (phraseType.equals(LvtbPmcTypes.SPCPMC) ||
				phraseType.equals(LvtbPmcTypes.QUOT) ||
				phraseType.equals(LvtbPmcTypes.ADDRESS))
			return s.allUnderFirstConstituent(phraseNode, LvtbRoles.BASELEM, addCoordPropCrosslinks, true);
		if (phraseType.equals(LvtbPmcTypes.INTERJ) ||
				phraseType.equals(LvtbPmcTypes.PARTICLE))
			return s.allUnderFirstConstituent(phraseNode, LvtbRoles.BASELEM, addCoordPropCrosslinks, false);

		//======= COORD ========================================================

		if (phraseType.equals(LvtbCoordTypes.CRDPARTS))
			return crdPartsToUD(phraseNode, addCoordPropCrosslinks);
		if (phraseType.equals(LvtbCoordTypes.CRDCLAUSES))
			return crdClausesToUD(phraseNode, addCoordPropCrosslinks);

		//======= X-WORD =======================================================

		// Multiple basElem, root is the last.
		if (phraseType.equals(LvtbXTypes.XAPP) ||
				phraseType.equals(LvtbXTypes.XNUM))
			return s.allUnderLastConstituent(phraseNode, LvtbRoles.BASELEM, null,
					addCoordPropCrosslinks, false);

		// Multiple basElem, root is the first.
		if (phraseType.equals(LvtbXTypes.XFUNCTOR) ||
				phraseType.equals(LvtbXTypes.PHRASELEM) ||
				phraseType.equals(LvtbXTypes.NAMEDENT) ||
				phraseType.equals(LvtbXTypes.COORDANAL))
			return s.allUnderFirstConstituent(phraseNode, LvtbRoles.BASELEM, addCoordPropCrosslinks,false);

		// Only one basElem
		if (phraseType.equals(LvtbXTypes.XPARTICLE))
			return s.allUnderLastConstituent(phraseNode, LvtbRoles.BASELEM, null,
					addCoordPropCrosslinks, true);
		if (phraseType.equals(LvtbXTypes.XPREP))
			return s.allUnderLastConstituent(phraseNode, LvtbRoles.BASELEM, LvtbRoles.PREP,
					addCoordPropCrosslinks, true);
		if (phraseType.equals(LvtbXTypes.XSIMILE))
			return xSimileToUD(phraseNode, addCoordPropCrosslinks);
		if (phraseType.equals(LvtbXTypes.UNSTRUCT))
			//return unstructToUd(phraseNode);
			return s.allUnderFirstConstituent(phraseNode, LvtbRoles.BASELEM,
					addCoordPropCrosslinks,false);

		// Specific.
		if (phraseType.equals(LvtbXTypes.SUBRANAL))
			return subrAnalToUD(phraseNode, addCoordPropCrosslinks);
		if (phraseType.equals(LvtbXTypes.XPRED))
			return xPredToUD(phraseNode, addCoordPropCrosslinks);

		StandardLogger.l.doInsentenceWarning(String.format(
				"Sentence \"%s\" has unrecognized \"%s\".", s.id, phraseType));
		//warnOut.printf("Sentence \"%s\" has unrecognized \"%s\".\n", s.id, phraseType);
		return missingTransform(phraseNode, addCoordPropCrosslinks);
	}

	/**
	 * Default phrase transformation: used when no phrase transformation rule
	 * is defined.
	 * @return PML A-level node: root of the corresponding UD structure.
	 */
	public PmlANode missingTransform(PmlANode phraseNode, boolean addCoordPropCrosslinks)
	{
		List<PmlANode> children = phraseNode.getChildren();
		PmlANode newRoot = PmlANodeListUtils.getFirstByDeepOrd(children);
		s.relinkAllConstituents(newRoot, children, phraseNode, addCoordPropCrosslinks);
		return newRoot;
	}

	/**
	 * Transformation for PMC that can have either basElem or pred - all
	 * children goes below first pred, r below forst basElem, if there is no
	 * pred.
	 * @return PML A-level node: root of the corresponding UD structure.
	 */
	protected PmlANode sentencyToUD(PmlANode pmcNode, boolean addCoordPropCrosslinks)
	{
		String pmcType = pmcNode.getPhraseType();
		List<PmlANode> children = pmcNode.getChildren();

		// Find the structure root.
		List<PmlANode> preds = pmcNode.getChildren(LvtbRoles.PRED);
		PmlANode newRoot = null;
		if (preds != null && preds.size() > 1)
			StandardLogger.l.doInsentenceWarning(String.format(
					"Sentence \"%s\" has more than one \"%s\" in \"%s\".",
					s.id, LvtbRoles.PRED, pmcType));
		if (preds == null || preds.isEmpty())
			preds = pmcNode.getChildren(LvtbRoles.BASELEM);
		newRoot = PmlANodeListUtils.getFirstByDeepOrd(preds);

		if (newRoot == null)
		{
			StandardLogger.l.doInsentenceWarning(String.format(
					"Sentence \"%s\" has no \"%s\", \"%s\" in \"%s\".",
					s.id, LvtbRoles.PRED, LvtbRoles.BASELEM, pmcType));
			newRoot = PmlANodeListUtils.getFirstByDeepOrd(children);
		}
		if (newRoot == null)
			throw new IllegalArgumentException(String.format(
					"Sentence \"%s\" seems to be empty", s.id));

		// Create dependency structure in conll table.
		s.relinkAllConstituents(newRoot, children, pmcNode, addCoordPropCrosslinks);

		return newRoot;
	}

	/**
	 * Transformation for PMC that can have either basElem or pred - all
	 * children goes below first pred, r below forst basElem, if there is no
	 * pred.
	 * @return PML A-level node: root of the corresponding UD structure.
	 */
	protected PmlANode utterToUD(PmlANode pmcNode, boolean addCoordPropCrosslinks)
	{
		String pmcType = pmcNode.getPhraseType();
		List<PmlANode> children = pmcNode.getChildren();

		// Find the structure root.
		List<PmlANode> basElems = pmcNode.getChildren(LvtbRoles.BASELEM);
		PmlANode newRoot = null;
		if (basElems != null && basElems.size() > 0)
			newRoot = PmlANodeListUtils.getFirstByDeepOrd(basElems);
		if (newRoot == null)
		{
			StandardLogger.l.doInsentenceWarning(String.format(
					"Sentence \"%s\" has no \"%s\" in \"%s\".",
					s.id, LvtbRoles.BASELEM, pmcType));
			newRoot = PmlANodeListUtils.getFirstByDeepOrd(children);
		}
		if (newRoot == null)
			throw new IllegalArgumentException(String.format(
					"Sentence \"%s\" seems to be empty", s.id));

		if (basElems!= null && basElems.size() > 1 && children.size() > basElems.size())
		{
			ArrayList<PmlANode> sortedChildren = PmlANodeListUtils.asOrderedList(children);
			ArrayList<PmlANode> rootChildren = new ArrayList<>();
			// If utter starts with punct, they are going to be root children.
			while (sortedChildren.size() > 0)
			{
				String role = sortedChildren.get(0).getRole();
				if (role.equals(LvtbRoles.PUNCT))
					rootChildren.add(sortedChildren.remove(0));
				else break;
			}
			// First "clause" until punctuation is going to be root children.
			while (sortedChildren.size() > 0)
			{
				String role = sortedChildren.get(0).getRole();
				if (!role.equals(LvtbRoles.PUNCT))
					rootChildren.add(sortedChildren.remove(0));
				else break;
			}
			// Last punctuation aslo is going to be root children.
			LinkedList<PmlANode> lastPunct = new LinkedList<>();
			while (sortedChildren.size() > 0)
			{
				String role = sortedChildren.get(sortedChildren.size()-1).getRole();
				if (role.equals(LvtbRoles.PUNCT))
					lastPunct.push(sortedChildren.remove(sortedChildren.size()-1));
				else break;
			}
			rootChildren.addAll(lastPunct);
			s.relinkAllConstituents(newRoot, rootChildren, pmcNode, addCoordPropCrosslinks);

			// now let's process what is left
			while (sortedChildren.size() > 0)
			{
				ArrayList<PmlANode> nextPart = new ArrayList<>();
				PmlANode subroot = null;

				// find next stop
				while (sortedChildren.size() > 0)
				{
					String role = sortedChildren.get(0).getRole();
					if (role.equals(LvtbRoles.PUNCT) && nextPart.size() > 0)
						break;
					else if (role.equals(LvtbRoles.BASELEM) && subroot == null)
						subroot = sortedChildren.get(0);
					nextPart.add(sortedChildren.remove(0));
				}

				// process found part
				s.relinkAllConstituents(subroot, nextPart, pmcNode, addCoordPropCrosslinks);

				// Is this really safe that all crosslinks have the same role???
				if (addCoordPropCrosslinks)
					s.setLinkAndCorsslinksPhrasal(newRoot, subroot, UDv2Relations.PARATAXIS,
							Tuple.of(UDv2Relations.PARATAXIS, null), true, true);
				else s.setLink(newRoot, subroot, UDv2Relations.PARATAXIS,
						Tuple.of(UDv2Relations.PARATAXIS, null), true, true);
			}
		}
		else s.relinkAllConstituents(newRoot, children, pmcNode, addCoordPropCrosslinks);

		return newRoot;
	}


	/**
	 * Transformation for coordinated parts - first coordinated part is used
	 * as root.
	 * @return PML A-level node: root of the corresponding UD structure.
	 */
	public PmlANode crdPartsToUD(PmlANode coordNode, boolean addCoordPropCrosslinks)
	{
		List<PmlANode> children = coordNode.getChildren();
		return coordPartsChildListToUD(
				PmlANodeListUtils.asOrderedList(children), coordNode, addCoordPropCrosslinks);
	}

	/**
	 * Transformation for coordinated clauses - part after semicolon is
	 * parataxis, otherwise the same as coordinated parts.
	 * @return PML A-level node: root of the corresponding UD structure.
	 */
	public PmlANode crdClausesToUD (PmlANode coordNode, boolean addCoordPropCrosslinks)
	{
		// Get all the children.
		//NodeList children = NodeUtils.getAllPMLChildren(coordNode);
		List<PmlANode> children = coordNode.getChildren();
		// Check if there are any semicolons.
		List<PmlANode> semicolons = new ArrayList<>();
		for (PmlANode child : children)
		{
			PmlMNode mNode = child.getM();
			if (mNode != null && ";".equals(mNode.getLemma()))
				semicolons.add(child);
		}
		ArrayList<PmlANode> sortedChildren = PmlANodeListUtils.asOrderedList(children);

		// No semicolons => process as ordinary coordination.
		if (semicolons.size() < 1)
			return coordPartsChildListToUD(sortedChildren, coordNode, addCoordPropCrosslinks);

		// If semicolon(s) is (are) present, split on semicolon and then process
		// each part as ordinary coordination.
		ArrayList<PmlANode> sortedSemicolons = PmlANodeListUtils.asOrderedList(semicolons);
		int semicOrd = sortedSemicolons.get(0).getOrd();
		PmlANode newRoot = coordPartsChildListToUD(
				PmlANodeListUtils.ordSplice(sortedChildren, 0, semicOrd),
				coordNode, addCoordPropCrosslinks);
		for (int i = 1; i < sortedSemicolons.size(); i++)
		{
			int nextSemicOrd = sortedSemicolons.get(i).getOrd();
			PmlANode newSubroot = coordPartsChildListToUD(
					PmlANodeListUtils.ordSplice(sortedChildren, semicOrd, nextSemicOrd),
					coordNode, addCoordPropCrosslinks);
			if (addCoordPropCrosslinks)
				s.setLinkAndCorsslinksPhrasal(newRoot, newSubroot, UDv2Relations.PARATAXIS,
						Tuple.of(UDv2Relations.PARATAXIS, null), true, true);
			else s.setLink(newRoot, newSubroot, UDv2Relations.PARATAXIS,
					Tuple.of(UDv2Relations.PARATAXIS, null), true, true);
			semicOrd = nextSemicOrd;
		}
		// last
		PmlANode newSubroot = coordPartsChildListToUD(
				PmlANodeListUtils.ordSplice(sortedChildren, semicOrd, Integer.MAX_VALUE),
				coordNode, addCoordPropCrosslinks);
		if (addCoordPropCrosslinks)
			s.setLinkAndCorsslinksPhrasal(newRoot, newSubroot, UDv2Relations.PARATAXIS,
					Tuple.of(UDv2Relations.PARATAXIS, null), true, true);
		else s.setLink(newRoot, newSubroot, UDv2Relations.PARATAXIS,
				Tuple.of(UDv2Relations.PARATAXIS, null), true, true);
		return newRoot;
	}

	/**
	 * Specific helper function, split out from coordination processing: do the
	 * transformation, assuming that the nodes provided as input node must be
	 * ordered as standard coordination structure, i.e., first crdPart is the
	 * root, all other crdPart-s are directly under it, all conj and punct are
	 * under the following crdPart.
	 * @return PML A-level node: root of the corresponding UD structure.
	 */
	protected PmlANode coordPartsChildListToUD(List<PmlANode> sortedNodes,
		   PmlANode coordNode, boolean addCoordPropCrosslinks)
	{
		// Find the structure root.
		PmlANode newRoot = null;
		PmlANode lastSubroot = null;
		ArrayList<PmlANode> postponed = new ArrayList<>();
		// First process all nodes that are followed by a crdPart node.
		for (PmlANode n : sortedNodes)
		{
			if (LvtbRoles.CRDPART.equals(n.getRole()))
			{
				s.relinkAllConstituents(n, postponed, coordNode, addCoordPropCrosslinks);
				lastSubroot = n;
				if (newRoot == null)
					newRoot = n;
				else
					s.relinkSingleConstituent(newRoot, n, coordNode, addCoordPropCrosslinks);
				postponed = new ArrayList<>();
			} else postponed.add(n);
		}
		// Then process what is left.
		if (!postponed.isEmpty())
		{
			if (lastSubroot != null)
				s.relinkAllConstituents(lastSubroot, postponed, coordNode, addCoordPropCrosslinks);
			else
			{
				StandardLogger.l.doInsentenceWarning(String.format(
						"Sentence \"%s\" has no \"%s\" in \"%s\".",
						s.id, LvtbRoles.CRDPART, coordNode.getPhraseType()));
				if (sortedNodes.get(0) != null )
				{
					newRoot = sortedNodes.get(0);
					s.relinkAllConstituents(newRoot, sortedNodes, coordNode, addCoordPropCrosslinks);
				}
				else throw new IllegalArgumentException(String.format(
						"\"%s\" in sentence \"%s\" seems to be empty",
						coordNode.getPhraseType(), s.id));
			}
		}
		return newRoot;
	}

	/*
	 * Transformation for unstruct x-word - if all parts are tagged as xf,
	 * DEPREL is foreign, else mwe.
	 * @return PML A-level node: root of the corresponding UD structure.
	 */
/*	@Deprecated
	public PmlANode unstructToUd(PmlANode xNode)
	{
		// Deprecated as currently foreign unstructs are annotated as xf.
		List<PmlANode> children = xNode.getChildren();
		List<PmlANode> foreigns = new ArrayList<>();
		List<PmlANode> punct = new ArrayList<>();
		for (PmlANode child : children)
		{
			PmlMNode morpho = child.getM();
			if (morpho == null) continue;
			String morphotag = morpho.getTag();
			if ("xf".equals(morphotag)) foreigns.add(child);
			else if (morphotag != null && morphotag.startsWith("z")) punct.add(child);
		}

		if (children.size() == foreigns.size()
			|| foreigns.size() > 0 && children.size() == foreigns.size() + punct.size())
			return s.allUnderFirstConstituent(xNode, LvtbRoles.BASELEM,
					Tuple.of(UDv2Relations.FLAT_FOREIGN, null), false, logger);
		else return s.allUnderFirstConstituent(xNode, LvtbRoles.BASELEM, null,
				false, logger);
	}//*/

	/**
	 * Transformation for subrAnal, based on subtag.
	 * @return PML A-level node: root of the corresponding UD structure.
	 */
	public PmlANode subrAnalToUD(PmlANode xNode, boolean addCoordPropCrosslinks)
	{
		String xTag = xNode.getPhraseTag();
		List<PmlANode> children = xNode.getChildren();
		if (xTag == null || xTag.isEmpty())
		{
			StandardLogger.l.doInsentenceWarning(String.format(
					"Sentence \"%s\" has \"%s\" with incomplete xTag \"%s\".",
					s.id, xNode.getPhraseType(), xTag));
			return missingTransform(xNode, addCoordPropCrosslinks);
		}
		//Matcher subTypeMatcher = Pattern.compile("[^\\[]*\\[(vv|ipv|skv|set|sal|part).*").matcher(xTag);
		Matcher subTypeMatcher = Pattern.compile("[^\\[]*\\[(vv|ipv|skv|set|sal).*")
				.matcher(xTag);
		if (!subTypeMatcher.matches())
		{
			StandardLogger.l.doInsentenceWarning(String.format(
					"Sentence \"%s\" has \"%s\" with incomplete xTag \"%s\".",
					s.id, xNode.getPhraseType(), xTag));
			return missingTransform(xNode, addCoordPropCrosslinks);
		}

		String subType = subTypeMatcher.group(1);
		switch (subType)
		{
			// TODO maybe this role choice should be moved to PhrasePartDepLogic.phrasePartRoleToUD()
			case "vv" : return s.allUnderFirstConstituent(
					xNode, LvtbRoles.BASELEM, addCoordPropCrosslinks, false);
			case "part" : return s.allUnderFirstConstituent(
					xNode, LvtbRoles.BASELEM, addCoordPropCrosslinks, false);
			case "ipv" :
			{
				List<PmlANode> basElems = xNode.getChildren(LvtbRoles.BASELEM);
				List<PmlANode> adjs = new ArrayList<>();
				for (PmlANode basElem : basElems)
				{
					String tag = basElem.getAnyTag();
					if (tag.matches("(a|ya|v..pd).*")) adjs.add(basElem);
				}
				if (adjs.size() < 1)
				{
					StandardLogger.l.doInsentenceWarning(String.format(
							"\"%s\" in sentence \"%s\" has no adjective \"%s\".",
							xNode.getPhraseType(), s.id, LvtbRoles.BASELEM));
					adjs = children;
				}
				else if (adjs.size() > 1)
					StandardLogger.l.doInsentenceWarning(String.format(
							"\"%s\" in sentence \"%s\" has more than one adjective \"%s\".",
							xNode.getPhraseType(), s.id, LvtbRoles.BASELEM));
					//warnOut.printf("\"%s\" in sentence \"%s\" has more than one adjective \"%s\".\n", xType, s.id, LvtbRoles.BASELEM);
				PmlANode newRoot = PmlANodeListUtils.getLastByDeepOrd(adjs);
				s.relinkAllConstituents(newRoot, children, xNode, addCoordPropCrosslinks);
				return newRoot;
			}
			case "skv" :
			{
				List<PmlANode> basElems = xNode.getChildren(LvtbRoles.BASELEM);
				List<PmlANode> prons = new ArrayList<>();
				for (PmlANode basElem : basElems)
				{
					String tag = basElem.getAnyTag();
					if (tag.matches("p.*")) prons.add(basElem);
				}
				if (prons.size() < 1)
				{
					StandardLogger.l.doInsentenceWarning(String.format(
							"\"%s\" in sentence \"%s\" has no pronominal \"%s\".",
							xNode.getPhraseType(), s.id, LvtbRoles.BASELEM));
					prons = children;
				}
				else if (prons.size() > 1)
					StandardLogger.l.doInsentenceWarning(String.format(
							"\"%s\" in sentence \"%s\" has more than one pronominal \"%s\".",
							xNode.getPhraseType(), s.id, LvtbRoles.BASELEM));
				PmlANode newRoot = PmlANodeListUtils.getFirstByDeepOrd(prons);
				s.relinkAllConstituents(newRoot, children, xNode,addCoordPropCrosslinks);
				return newRoot;
			}
			case "set" :
			{
				List<PmlANode> basElems = xNode.getChildren(LvtbRoles.BASELEM);
				List<PmlANode> noPrepBases = new ArrayList<>();
				for (PmlANode basElem : basElems)
				{
					PmlANode phrase = basElem.getPhraseNode();
					if (phrase == null || phrase.getNodeType() != PmlANode.Type.X
							|| !LvtbXTypes.XPREP.equals(phrase.getPhraseType()))
						noPrepBases.add(basElem);
				}

				if (noPrepBases.size() < 1)
				{
					StandardLogger.l.doInsentenceWarning(String.format(
							"\"%s\" in sentence \"%s\" has no \"%s\" without \"%s\".",
							xNode.getPhraseType(), s.id, LvtbRoles.BASELEM, LvtbXTypes.XPREP));
					//warnOut.printf("\"%s\" in sentence \"%s\" has no \"%s\" without \"%s\".\n", xType, s.id, LvtbRoles.BASELEM, LvtbXTypes.XPREP);
					noPrepBases = children;
				}
				else if (noPrepBases.size() > 1)
					StandardLogger.l.doInsentenceWarning(String.format(
							"\"%s\" in sentence \"%s\" has more than one \"%s\" without \"%s\".",
							xNode.getPhraseType(), s.id, LvtbRoles.BASELEM, LvtbXTypes.XPREP));
					//warnOut.printf("\"%s\" in sentence \"%s\" has more than one \"%s\" without \"%s\".\n", xType, s.id, LvtbRoles.BASELEM, LvtbXTypes.XPREP);
				PmlANode newRoot = PmlANodeListUtils.getLastByDeepOrd(noPrepBases);
				s.relinkAllConstituents(newRoot, children, xNode, addCoordPropCrosslinks);
				return newRoot;
			}
			case "sal" :
			{
				List<PmlANode> basElems = xNode.getChildren(LvtbRoles.BASELEM);
				List<PmlANode> noSimBases = new ArrayList<>();
				for (PmlANode basElem : basElems)
				{
					PmlANode phrase = basElem.getPhraseNode();
					if (phrase == null || phrase.getNodeType() != PmlANode.Type.X
							&& !LvtbXTypes.XSIMILE.equals(phrase.getPhraseType()))
						noSimBases.add(basElem);
				}

				if (noSimBases.size() < 1)
				{
					StandardLogger.l.doInsentenceWarning(String.format(
							"\"%s\" in sentence \"%s\" has no \"%s\" without \"%s\".",
							xNode.getPhraseType(), s.id, LvtbRoles.BASELEM, LvtbXTypes.XSIMILE));
					noSimBases = children;
				}
				else if (noSimBases.size() > 1)
					StandardLogger.l.doInsentenceWarning(String.format(
							"\"%s\" in sentence \"%s\" has more than one \"%s\" without \"%s\".",
							xNode.getPhraseType(), s.id, LvtbRoles.BASELEM, LvtbXTypes.XSIMILE));
				PmlANode newRoot = PmlANodeListUtils.getLastByDeepOrd(noSimBases);
				s.relinkAllConstituents(newRoot, children, xNode, addCoordPropCrosslinks);
				return newRoot;
			}
		}
		StandardLogger.l.doInsentenceWarning(String.format(
				"Sentence \"%s\" has \"%s\" with incomplete xTag \"%s\".",
				s.id, xNode.getPhraseType(), xTag));
		return missingTransform(xNode, addCoordPropCrosslinks);
	}

	/**
	 * Transformation for xSimile construction. Grammaticalization feature in
	 * xTag is required for successful transformation.
	 * @return PML A-level node: root of the corresponding UD structure.
	 */
	public PmlANode xSimileToUD(PmlANode xNode, boolean addCoordPropCrosslinks)
	{
		String xTag = xNode.getPhraseTag();
		if (xTag == null || xTag.isEmpty() || !xTag.matches("[^\\[]*\\[(sim|comp)[yn].*"))
		{
			StandardLogger.l.doInsentenceWarning(String.format(
					"Sentence \"%s\" has \"%s\" with incomplete xTag \"%s\".",
					s.id, xNode.getPhraseType(), xTag));
			return missingTransform(xNode, addCoordPropCrosslinks);
		}
		boolean gramzed = xTag.matches("[^\\[]*\\[(sim|comp)y.*");
		if (gramzed)
		{
			//NodeList children = NodeUtils.getAllPMLChildren(xNode);
			List<PmlANode> children = xNode.getChildren();
			PmlANode newRoot = PmlANodeListUtils.getFirstByDeepOrd(children);
			s.relinkAllConstituents(newRoot, children, xNode, true);
			return newRoot;
		}
		return s.allUnderLastConstituent(xNode, LvtbRoles.BASELEM, null,
				addCoordPropCrosslinks, true);
	}

	/**
	 * Transformation for complex predicates. Predicates are expected to have
	 * only one basElem and either one mod or some aux'es. In case of mod,
	 * baseElem is attached as xcomp to it. Otherwise, noModXPredUD() are used.
	 * @return PML A-level node: root of the corresponding UD structure.
	 */
	public PmlANode xPredToUD(PmlANode xNode, boolean addCoordPropCrosslinks)
	{
		List<PmlANode> children = xNode.getChildren();
		if (children.size() == 1) return children.get(0);
		List<PmlANode> mods = xNode.getChildren(LvtbRoles.MOD);
		if (mods == null || mods.size() < 1)
			return noModXPredToUD(xNode, addCoordPropCrosslinks);
		else return modXPredToUD(xNode, addCoordPropCrosslinks);
	}

	/**
	 * Specific helper function: implementation of modal predication logic,
	 * split out from xPred processing.
	 * @return	PML A-level node: root of the corresponding UD structure.
	 */
	protected PmlANode modXPredToUD(PmlANode xNode, boolean addCoordPropCrosslinks)
	{
		// Check if the tag is appropriate.
		String xTag = xNode.getPhraseTag();
		String subtag = (xTag != null && xTag.contains("[") ?
				xTag.substring(xTag.indexOf("[") + 1) : "");
		if (!subtag.startsWith("modal") && !subtag.startsWith("expr")
				&& !subtag.startsWith("phase"))
			StandardLogger.l.doInsentenceWarning(String.format(
					"xPred \"%s\" has a problematic tag \"%s\".",
					xNode.getParent().getId(), xTag));
		// Just put basElem under mod.
		return s.allUnderLastConstituent(xNode, LvtbRoles.MOD, LvtbRoles.BASELEM,
				addCoordPropCrosslinks, true);
	}

	/**
	 * Specific helper function: implementation of aux/auxpass/cop logic, split
	 * out from xPred processing.
	 * @return	PML A-level node: root of the corresponding UD structure.
	 */
	protected PmlANode noModXPredToUD(PmlANode xNode, boolean addCoordPropCrosslinks)
	{
		// Get basElems and warn if there is none.
		List<PmlANode> basElems = xNode.getChildren(LvtbRoles.BASELEM);
		PmlANode basElem = PmlANodeListUtils.getLastByDeepOrd(basElems);
		if (basElem == null)
			throw new IllegalArgumentException(String.format(
					"\"%s\" in sentence \"%s\" has no \"basElem\"",
					xNode.getPhraseType(), s.id));
		List<PmlANode> auxes = xNode.getChildren(LvtbRoles.AUXVERB);
		PmlANode lastAux = PmlANodeListUtils.getLastByDeepOrd(auxes);
		if (lastAux == null)
			throw new IllegalArgumentException(String.format(
					"\"%s\" in sentence \"%s\" has neither \"auxVerb\" nor \"mod\"",
					xNode.getPhraseType(), s.id));
		if (auxes.size() > 1) for (int i = 0; i < auxes.size(); i++)
		{
			String auxLemma = lastAux.getM().getLemma();
			String auxRedLemma = lastAux.getReductionLemma();
			if (auxRedLemma == null) auxRedLemma = ""; // So regexp matching would not fail.
			if (!auxLemma.matches("(ne)?(būt|tikt|tapt|kļūt)") &&
					!auxRedLemma.matches("(ne)?(būt|tikt|tapt|kļūt)"))
				StandardLogger.l.doInsentenceWarning(String.format(
						"xPred \"%s\" has multiple auxVerb one of which has lemma \"%s\".",
						xNode.getParent().getId(), auxLemma));
		}

		PmlMNode lastAuxM = lastAux.getM();
		String auxLemma = lastAuxM == null ? null : lastAuxM.getLemma();
		String auxRedLemma = lastAux.getReductionLemma();
		boolean ultimateAux =
				auxLemma != null && auxLemma.matches("(ne)?(būt|kļūt|tikt|tapt)") ||
				auxRedLemma != null && auxRedLemma.matches("(ne)?(būt|kļūt|tikt|tapt)");

		PmlANode newRoot = basElem;
		if (!ultimateAux) newRoot = lastAux;
		List<PmlANode> children = xNode.getChildren();
		s.relinkAllConstituents(newRoot, children, xNode, addCoordPropCrosslinks);

		/* //Not needed as s.relinkAllConstituents() should set the correct roles.
		if (passive && ultimateAux)
			s.setLink(newRoot, lastAux, UDv2Relations.AUX_PASS,
					Tuple.of(UDv2Relations.AUX_PASS, null), true, true);
		if (nominal && ultimateAux)
			s.setLink(newRoot, lastAux, UDv2Relations.COP,
					Tuple.of(UDv2Relations.COP, null), true, true);*/
		return newRoot;
	}
}
