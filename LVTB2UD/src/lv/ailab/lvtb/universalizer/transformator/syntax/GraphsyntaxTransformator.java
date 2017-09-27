package lv.ailab.lvtb.universalizer.transformator.syntax;

import lv.ailab.lvtb.universalizer.conllu.EnhencedDep;
import lv.ailab.lvtb.universalizer.conllu.Token;
import lv.ailab.lvtb.universalizer.conllu.UDv2Relations;
import lv.ailab.lvtb.universalizer.pml.LvtbRoles;
import lv.ailab.lvtb.universalizer.pml.Utils;
import lv.ailab.lvtb.universalizer.transformator.Sentence;
import lv.ailab.lvtb.universalizer.util.XPathEngine;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashSet;

public class GraphsyntaxTransformator
{
	/**
	 * In this sentence all the transformations are carried out.
	 */
	public Sentence s;
	protected PrintWriter warnOut;

	public GraphsyntaxTransformator(Sentence sent, PrintWriter warnOut)
	{
		s = sent;
		this.warnOut = warnOut;
	}

	public void transformEnhancedSyntax() throws XPathExpressionException
	{
		s.populateCoordPartsUnder();
		propagateConjuncts();
		addControlledSubjects();
	}

	protected void addControlledSubjects() throws XPathExpressionException
	{
		// Find all nodes consisting of xPred with dependant subj.
		NodeList xPredList = (NodeList) XPathEngine.get().evaluate(
				".//node[children/xinfo/xtype/text()='xPred']",
				s.pmlTree, XPathConstants.NODESET);
		if (xPredList != null)
			for (int xPredI = 0; xPredI < xPredList.getLength(); xPredI++)
		{
			// Get base token.
			Token parentTok = s.getEnhancedOrBaseToken(xPredList.item(xPredI));

			// Collect all subject nodes.
			ArrayList<Node> subjs = new ArrayList<>();
			NodeList tmp = (NodeList) XPathEngine.get().evaluate(
					"./children/node[role/text()='subj']", xPredList.item(xPredI), XPathConstants.NODESET);
			if (tmp != null) subjs.addAll(Utils.asList(tmp));
			Node ancestor = Utils.getPMLParent(xPredList.item(xPredI));
			while (ancestor.getNodeName().equals("coordinfo"))
			{
				ancestor = Utils.getPMLParent(ancestor); // PML node
				tmp = (NodeList) XPathEngine.get().evaluate(
						"./children/node[role/text()='subj']", ancestor , XPathConstants.NODESET);
				if (tmp != null) subjs.addAll(Utils.asList(tmp));
				ancestor = Utils.getPMLParent(ancestor); // PML node or phrase
			}
			// If no subjects found, nothing to do.
			if (subjs.isEmpty()) continue;

			// Work on each xPred part
			NodeList xPredParts = Utils.getPMLNodeChildren(Utils.getPhraseNode(xPredList.item(xPredI)));
			if (xPredParts != null)
				for (int xPredPartI = 0; xPredPartI < xPredParts.getLength(); xPredPartI++)
			{
				// Do nothing with auxiliaries
				Token xPredPartTok = s.getEnhancedOrBaseToken(xPredParts.item(xPredPartI));
				if (xPredPartTok.depsBackbone.role == UDv2Relations.AUX
						|| xPredPartTok.depsBackbone.role == UDv2Relations.AUX_PASS
						|| xPredPartTok.depsBackbone.role == UDv2Relations.COP)
					continue;

				// For each other part a lingk between each subject and this
				// part must be made.

				for (Node subj : subjs)
				{
					Token subjTok = s.getEnhancedOrBaseToken(subj);
					UDv2Relations role = subjTok.depsBackbone.role;
					// Only UD subjects will have aditional link.
					if (role != UDv2Relations.NSUBJ && role != UDv2Relations.NSUBJ_PASS &&
							role != UDv2Relations.CSUBJ && role != UDv2Relations.CSUBJ_PASS)
						continue;

					// Find each coordinated subject part.
					HashSet<String> subjIds = s.getCoordPartsUnderOrNode(subj);
					// Find each coordinated x-part part.
					HashSet<String> xPartIds = s.getCoordPartsUnderOrNode(xPredParts.item(xPredPartI));
					// Make a link.
					for (String subjId : subjIds)
					{
						Node subjNode = s.findPmlNode(subjId);
						for (String xPartId : xPartIds)
						{
							Node xPartNode = s.findPmlNode(xPartId);
							s.setEnhLink(xPartNode, subjNode, role, false, false);
						}
					}
				}
			}
		}
	}

	protected void propagateConjuncts() throws XPathExpressionException
	{
		for (String coordId : s.coordPartsUnder.keySet())
		{
			Node parentNode = s.findPmlNode(coordId);
			Token parentNodeTok = s.getEnhancedOrBaseToken(parentNode);

			Node grandParentNode = Utils.getPMLParent(parentNode);
			Node grandGrandParentNode = Utils.getPMLParent(grandParentNode);

			for (String coordPartId : s.coordPartsUnder.get(coordId))
			{
				Node partNode = s.findPmlNode(coordPartId);
				Token partNodeTok = s.getEnhancedOrBaseToken(partNode);
				if (!partNodeTok.equals(parentNodeTok))
				{
					// Link between parent of the coordination and coordinated part.
					if (!parentNodeTok.depsBackbone.equals(EnhencedDep.root()))
						partNodeTok.deps.add(parentNodeTok.depsBackbone);

					// Links between dependants of the coordination and coordinated parts.
					NodeList dependents = Utils.getPMLNodeChildren(parentNode);
					if (dependents != null)
						for (int dependentI =0; dependentI < dependents.getLength(); dependentI++)
					{
						UDv2Relations role = DepRelLogic.getSingleton().depToUD(
								dependents.item(dependentI), true, warnOut);
						s.setEnhLink(partNode, dependents.item(dependentI), role,false,false);
					}

					// Links between phrase parts
					//if (Utils.isPhraseNode(grandParentNode))
					if (grandParentNode.getNodeName().equals("xinfo")
							|| grandParentNode.getNodeName().equals("pmcinfo"))
					{
						// Renaming for convenience
						Node phrase = grandParentNode;
						Node phraseParent = grandGrandParentNode;
						Token phraseRootToken = s.getEnhancedOrBaseToken(phraseParent);
						NodeList phraseParts = Utils.getPMLNodeChildren(phrase);
						if (phraseParts != null)
							for (int phrasePartI = 0; phrasePartI < phraseParts.getLength(); phrasePartI++)
						{
							if (phraseParts.item(phrasePartI).isSameNode(partNode)
									||Utils.getAnyLabel(phraseParts.item(phrasePartI)).equals(LvtbRoles.PUNCT))
								continue;

							Token otherPartToken = s.getEnhancedOrBaseToken(phraseParts.item(phrasePartI));
							if (otherPartToken.depsBackbone.headID.equals(phraseRootToken.getFirstColumn()))
								s.setEnhLink(partNode, phraseParts.item(phrasePartI),
										otherPartToken.depsBackbone.role, false, false);
						}
					}
				}
			}
		}
	}

/*	protected void propagateConjuncts() throws XPathExpressionException
	{
		NodeList crdPartList = (NodeList) XPathEngine.get().evaluate(
				".//node[role/text()=\"crdPart\"]", s.pmlTree, XPathConstants.NODESET);
		if (crdPartList != null) for (int i = 0; i < crdPartList.getLength(); i++)
		{
			// Let's find effective parent - not coordination.
			Node effParent = Utils.getEffectiveAncestor(crdPartList.item(i));

			// Link between parent of the coordination and coordinated part.
			Token effParentTok = s.getEnhancedOrBaseToken(effParent);
			Token childTok = s.getEnhancedOrBaseToken(crdPartList.item(i));
			if (!effParentTok.depsBackbone.equals(EnhencedDep.root()))
				childTok.deps.add(effParentTok.depsBackbone);

			// Links between dependants of the coordination and coordinated parts.
			// TODO grandparents.
			NodeList dependants = Utils.getPMLNodeChildren(effParent);
			if (dependants != null) for (int j =0; j < dependants.getLength(); j++)
			{
				UDv2Relations role = DepRelLogic.getSingleton().depToUD(
						dependants.item(j), true, warnOut);
				s.setEnhLink(crdPartList.item(i), dependants.item(j), role,false,false);
			}

			// Links between phrase parts
			Node specialPPart = effParent; // PML A node.
			Node phrase = Utils.getPMLParent(specialPPart); // PML phrase or A node/root in the end of the loop.
			Node phraseParent = Utils.getPMLParent(phrase);
			//Node specialPPart = crdPartList.item(i); // PML A node.
			while (phrase != null && Utils.isPhraseNode(phrase))// && s.pmlaToConll.get(Utils.getId(specialPPart)).equals(s.pmlaToConll.get(Utils.getId(phraseParent))))
			{
				Token phraseRootToken = s.getEnhancedOrBaseToken(phraseParent);
				NodeList phraseParts = Utils.getPMLNodeChildren(phrase);
				if (phraseParts != null) for (int j = 0; j < phraseParts.getLength(); j++)
				{
					if (phraseParts.item(j).isSameNode(specialPPart)) continue;
					if (Utils.getAnyLabel(phraseParts.item(j)).equals(LvtbRoles.PUNCT)) continue;

					Token otherPartToken = s.getEnhancedOrBaseToken(phraseParts.item(j));
					if (otherPartToken.depsBackbone.headID.equals(phraseRootToken.getFirstColumn()))
						s.setEnhLink(crdPartList.item(i), phraseParts.item(j),
								otherPartToken.depsBackbone.role,false,false);
				}

				// Path to root goes like this: phrase->node->phrase->node...
				// Must stop, when ...->node->node happens.
				specialPPart = phraseParent;
				phrase = Utils.getEffectiveAncestor(phraseParent);
				phraseParent = Utils.getPMLParent(phrase);
			}
		}
	}//*/

}
