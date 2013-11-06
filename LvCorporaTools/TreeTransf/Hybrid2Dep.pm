#!C:\strawberry\perl\bin\perl -w
package LvCorporaTools::TreeTransf::Hybrid2Dep;

use strict;
use warnings;
#use utf8;

use Exporter();
our @ISA = qw(Exporter);
our @EXPORT_OK = qw(
	$XPRED $COORD $PMC $LABEL_ROOT $LABEL_SUBROOT $LABEL_PHRASE_DEP
	$LABEL_DETAIL_NA transformFile processDir transformTree);
	
use Carp::Always;	# Print stack trace on die.

use File::Path;
use IO::File;
use IO::Dir;
use List::Util qw(first);
use XML::LibXML;  # XML handling library

use LvCorporaTools::GenericUtils::UIWrapper;
use LvCorporaTools::PMLUtils::AUtils qw(
	renumberNodes renumberTokens getRole setNodeRole getChildrenNode
	moveChildren getOrd sortNodesByOrd hasPhraseChild);

###############################################################################
# This program transforms Latvian Treebank analytical layer files from native
# hybrid format to dependency-only simplification. Input files are supposed to
# be valid against coresponding PML schemas. Invalid features like multiple
# roles, ords per single node are not checked.
#
# Works with A level schema v.2.12.
# Input files - utf8.
#
# Developed on Strawberry Perl 5.12.3.0
# Latvian Treebank project, 2012-2013
# Lauma Pretkalniņa, LUMII, AILab, lauma@ailab.lv
# Licenced under GPL.
###############################################################################

# Global variables set how to transform specific elements.
# Unknown values cause fatal error.

#our $XPRED = 'BASELEM';	# auxverbs and modals below basElem
our $XPRED = 'DEFAULT'; 	# everything below first auxverb/modal

#our $COORD = 'ROW';		# all coordination elements in a row except conj
							# before first conjunct
#our $COORD = 'ROW_NO_CONJ';# conjuncts in a row, conjunctions and punctuatuon
							# below following conjunct
our $COORD = 'DEFAULT'; 	# conjunction or punctuation as root element

#our $PMC = 'BASELEM';		# basElem as root element
our $PMC = 'DEFAULT';		# first punct as root element

our $LABEL_ROOT = 1;		# Label tree's empty root node 'ROOT'.
#our $LABEL_ROOT = 0;		# Do not label root node.

our $LABEL_SUBROOT = 1;		# Both phrase name and child role is added to the
							# child in the root of the phrase representing
							# subtree for all phrase types.
#our $LABEL_SUBROOT = 0;	# Leave out phrase name and child role for the
							# child in the root of the phrase representing
							# subtree. This done for selected phrase types
							# only: xParticle, subrAnal, coordAnal, xNum, xPred
							# (if $XPRED='BASELEM'), xApp, namedEnt,
							# [phrasElem,] unstruct; crdParts (if
							# $COORD='ROW' or $COORD='ROW_NO_CONJ'), crdClauses
							# (if $COORD='ROW' or $COORD='ROW_NO_CONJ'),
							# crdGeneral (if $COORD='ROW' or
							# $COORD='ROW_NO_CONJ'); spcPmc (if
							# $PMC='BASELEM'), quot (if $PMC='BASELEM').

our $LABEL_PHRASE_DEP = 0;	# Use one prefix for all dependency roles. 
#our $LABEL_PHRASE_DEP = 1;	# Asign different role prefix to dependencies whose
							# head is phase.

our $LABEL_DETAIL_NA = 0;	# All roles containing N/A rename as just 'N/A'.
#our $LABEL_DETAIL_NA = 1;	# Treat N/A as every other role (allow it to be part
							# of longer role)

# Process all .a files in given folder. This can be used as entry point, if
# this module is used standalone.
sub processDir
{
	autoflush STDOUT 1;
	if (not @_ or @_ < 1)
	{
		print <<END;
Script for batch transfoming Latvian Treebank .a files from native hybrid
format to dependency-only format.
Global variables:
   XPRED - xPred transformation: 'BASELEM' (auxverbs and modals become
           dependents of basElem) / 'DEFAULT' (everything become dependent of
           first auxverb/modal, default value)
   COORD - coordinated elements' transformation: 'ROW' (all coordination
           elements in a row) / ROW_NO_CONJ (all conjuncts in a row,
           conjunctions and punctuation under following conjunct / 'DEFAULT'
           (conjunction or punctuation as root element, default value)
   PMC - punctuation mark constucts' transformation: 'BASELEM' (basElem
         becomes root element) / 'DEFAULT' (first punct becomes root element,
         default value)
   LABEL_ROOT - add label 'ROOT' to hybrid tree's root node: 0 (no) / 1 (yes,
                default value)
   LABEL_SUBROOT - leave out phrase name and child role for the child in the
                   root of the phrase representing subtree (this done for
                   selected phrase types only): 0 / both phrase name and child
                   role is added to the child in the root of the phrase
                   representing subtree for all phrase types: 1
   LABEL_PHRASE_DEP - asign different role prefix to dependencies whose head
                      is phase: 0 (use one prefix for all dependency roles,
                      default value) / 1
   LABEL_DETAIL_NA - allow roles containing 'N/A' as a part of them: 0 (no,
                     all such roles are renamed just 'N/A', default value) / 1
                     (yes, label 'N/A' is procesed as every other label)
Input files should be provided as UTF-8.

Params:
   data directory 
   input data have all nodes ordered [opt, 0/1, 0 (no) assumed by default]

Latvian Treebank project, LUMII, 2013, provided under GPL
END
		exit 1;
	}

	LvCorporaTools::GenericUtils::UIWrapper::processDir(
		\&transformFile, "^.+\\.a\$", '-dep.a', 1, 0, @_);
}


# Process single XML file. This can be used as entry point, if this module
# is used standalone.
sub transformFile
{
	autoflush STDOUT 1;
	if (not @_ or @_ < 2)
	{
		print <<END;
Script for transfoming Latvian Treebank .a files from native hybrid format to
dependency-only format.
Global variables:
   XPRED - xPred transformation: 'BASELEM' (auxverbs and modals become
           dependents of basElem) / 'DEFAULT' (everything become dependent of
           first auxverb/modal, default value)
   COORD - coordinated elements' transformation: 'ROW' (all coordination
           elements in a row) / ROW_NO_CONJ (all conjuncts in a row,
           conjunctions and punctuation under following conjunct / 'DEFAULT'
           (conjunction or punctuation as root element, default value)
   PMC - punctuation mark constucts' transformation: 'BASELEM' (basElem
         becomes root element) / 'DEFAULT' (first punct becomes root element,
         default value)
   LABEL_ROOT - add label 'ROOT' to hybrid tree's root node: 0 (no) / 1 (yes,
                default value)
   LABEL_SUBROOT - leave out phrase name and child role for the child in the
                   root of the phrase representing subtree (this done for
                   selected phrase types only): 0 / both phrase name and child
                   role is added to the child in the root of the phrase
                   representing subtree for all phrase types: 1
   LABEL_PHRASE_DEP - asign different role prefix to dependencies whose head
                      is phase: 0 (use one prefix for all dependency roles,
                      default value) / 1
   LABEL_DETAIL_NA - allow roles containing 'N/A' as a part of them: 0 (no,
                     all such roles are renamed just 'N/A', default value) / 1
                     (yes, label 'N/A' is procesed as every other label)
Input files should be provided as UTF-8.

Params:
   directory prefix
   file name
   new file name [opt, current file name used otherwise]
   input data have all nodes ordered [opt, 0/1, 0 (no) assumed by default]

Latvian Treebank project, LUMII, 2013, provided under GPL
END
		exit 1;
	}
	# Input paramaters.
	my $dirPrefix = shift @_;
	my $oldName = shift @_;
	my $newName = (shift @_ or $oldName);
	my $numberedNodes = (shift @_ or 0);
	
	# This is how each tree sould be processed.
	my $treeProc = sub {
		renumberNodes(@_) unless ($numberedNodes);
		&transformTree(@_);
	};
	
	# File procesing wrapper customised with tree processing instructions.
	LvCorporaTools::GenericUtils::UIWrapper::transformAFile(
		$treeProc, 1, 1, 'lvaschema-deponly.xml', 'lvadepdata', $dirPrefix,
		$oldName, $newName);

}

# Transform single tee (LM element in most tree files).
# transformTree (XPath context with set namespaces, DOM node for tree root
#				 (usualy "LM"), output flow for warnings)
sub transformTree
{
	my $xpc = shift @_; # XPath context
	my $tree = shift @_;
	my $warnFile = shift @_;
	my $role = '';
	$role = 'ROOT' if ($LABEL_ROOT);
	
	# Well, actually, for valid trees there should be only one children node.
	foreach my $childrenWrap ($xpc->findnodes('pml:children', $tree))
	{
		my @phrases = $xpc->findnodes(
			'pml:children/*[local-name()!=\'node\']',
			$tree);
		die ($tree->find('@id'))." has ".(scalar @phrases)." non-node children for the root!"
			if (scalar @phrases ne 1);
		

		# Process PMC (probably) node.
		my $chRole = getRole($xpc, $phrases[0]);
		my $newNode = &{\&{$chRole}}($xpc, $phrases[0], $role, $warnFile); # Function call by role name.
		# Reset dependents' roles.
		my $hasPhChild = hasPhraseChild($xpc, $tree); # This should always be true.
		foreach my $ch ($xpc->findnodes('pml:children/pml:node', $tree))
		{
			&_renameDependent($xpc, $ch, $hasPhChild);
		}
		moveChildren($xpc, $tree, $newNode);
		# Add reformed subtree to the main tree.
		$phrases[0]->replaceNode($newNode);

		# Process children.
		foreach my $ch ($xpc->findnodes('pml:children/pml:node', $newNode))
		{
			&_transformSubtree($xpc, $ch, $warnFile);
		}
		&_transformSubtree($xpc, $newNode, $warnFile);

	}
	
	#&_recalculateOrds($xpc, $tree);
	renumberTokens($xpc, $tree);

	# &_finishRoles($xpc, $tree);
}

# Traversal function for procesing any subtree except "the big Tree" starting
# _transformSubtree (XPath context with set namespaces, DOM "node" node for
#					 subtree root, output flow for warnings)
sub _transformSubtree
{
	my $xpc = shift @_; # XPath context
	my $node = shift @_;
	my $warnFile = shift @_;
	my $role = getRole($xpc, $node);

	# Reset dependents' roles.
	my $hasPhChild = hasPhraseChild($xpc, $node); 
	foreach my $ch ($xpc->findnodes('pml:children/pml:node', $node))
	{
		&_renameDependent($xpc, $ch, $hasPhChild);
	}

	# Find phrase nodes.
	my @phrases = $xpc->findnodes(
		#'pml:children/pml:xinfo|pml:children/pml:coordinfo|pml:children/pml:pmcinfo',
		'pml:children/*[local-name()!=\'node\']',
		$node);
	# A bit structure checking: only one phrase per regular node is allowed.
	die (($node->find('@id'))." has ".(scalar @phrases)." non-node children!")
		if (scalar @phrases gt 1);
	
	# If there is no phrase children, process dependency children and finish.
	if (scalar @phrases lt 1)
	{
		# Process dependency children.
		foreach my $ch ($xpc->findnodes('pml:children/pml:node', $node))
		{
			&_transformSubtree($xpc, $ch, $warnFile);
		}
		return;
	}
	
	# A bit structure checking: phrase can't have another phrase as direct child.
	my @phrasePhrases = $xpc->findnodes(
		'pml:children/*[local-name()!=\'node\']', $phrases[0]);
	die (($node->find('@id'))." has illegal phrase cascade as child!")
		if (scalar @phrasePhrases gt 0);
	
	# Process phrase node.
	my $phRole = getRole($xpc, $phrases[0]);
	my $newNode = &{\&{$phRole}}($xpc, $phrases[0], $role, $warnFile); #Function call by role name.
	moveChildren($xpc, $node, $newNode);
	$node->replaceNode($newNode);

	# Process childen.
	foreach my $ch ($xpc->findnodes('pml:children/pml:node', $newNode))
	{
		&_transformSubtree($xpc, $ch, $warnFile);
	}
	&_transformSubtree($xpc, $newNode, $warnFile);
}

# Role transformations

sub _renamePhraseChild
{
	my $xpc = shift @_; # XPath context
	my $node = shift @_;
	my $phraseRole = shift @_;
	my $nodeRole = getRole($xpc, $node);
	
	my $newRole = "$phraseRole:$nodeRole";
	$newRole = 'N/A' if (not $LABEL_DETAIL_NA and $newRole =~ m#N/A#);
	setNodeRole($xpc, $node, $newRole);
}
sub _renamePhraseSubroot
{
	my $labelSubroot = shift @_;
	my $xpc = shift @_; # XPath context
	my $node = shift @_;
	my $parentRole = shift @_;
	my $phraseRole = shift @_;
	my $nodeRole = getRole($xpc, $node);
	
	my $newRole = ($labelSubroot or $LABEL_SUBROOT) ? "$phraseRole:$nodeRole" : '';
	$newRole = "-$newRole" if ($parentRole and $newRole);
	$newRole = "$parentRole$newRole" if ($parentRole);
	$newRole = 'N/A' if (not $LABEL_DETAIL_NA and $newRole =~ m#N/A#);	
	setNodeRole($xpc, $node, $newRole);
}
sub _renameDependent
{
	my $xpc = shift @_; # XPath context
	my $node = shift @_;
	my $isParentPhrase = shift @_;
	my $nodeRole = getRole($xpc, $node);
	
	my $prefix = ($LABEL_PHRASE_DEP and $isParentPhrase) ? 'phdep' : 'dep';
	my $newRole = $nodeRole;
	$newRole = "$prefix:$newRole"
		if ($nodeRole =~ /^[^:]+-/ or $nodeRole !~ /:/);
	$newRole = 'N/A' if (not $LABEL_DETAIL_NA and $newRole =~ m#N/A#);	
	setNodeRole($xpc, $node, $newRole);
}

###############################################################################
# Phrase specific functions (does not process phrase constituent children, this
# is responsibility of &_transformSubtree.
# phrase_name (XPath context with set namespaces, DOM "xinfo" or "coordinfo" or
#			   "pmcinfo" node for subtree root, parent role, output flow for
#			   warnings)
###############################################################################

### X-words ###################################################################
sub xPrep
{
	return &_allBelowOne(['prep'], 1, 1, @_);
	# Root is labeled always.
}
sub xSimile
{
	return &_allBelowOne(['conj'], 1, 1, @_);
	# Root is labeled always.
}
sub xParticle
{
	return &_allBelowOne(['basElem'], $LABEL_SUBROOT, 1, @_);
	# Root is labeled according to settings.
}
sub subrAnal
{
	return &_allBelowOne(['basElem'], $LABEL_SUBROOT, 0, @_);
	# Root is labeled according to settings.
}
sub coordAnal
{
	my $xpc = $_[0]; # XPath context
	my $node = $_[1];
	my $parentRole = $_[2];
	my $warnFile = $_[3];

	# Warning about suspective structure.
	my @ch = $xpc->findnodes('pml:children/pml:node', $node);
	if (@ch != 2 and $parentRole eq 'coordAnal')
	{
		print "$parentRole has ".(scalar @ch)." children.\n";
		print $warnFile "$parentRole below ". $node->find('../../@id').' has '
				.(scalar @ch)." children.\n";
	}
	return &_chainAll(0, $LABEL_SUBROOT, @_);
	# Root is labeled according to settings.
}
sub xNum
{
	return &_chainAll(1, $LABEL_SUBROOT, @_);
	# Root is labeled according to settings.
}
sub xPred
{
	return &_allBelowOne(['basElem'], $LABEL_SUBROOT, 1, @_) if ($XPRED eq 'BASELEM');
	return &_allBelowOne(['mod', 'auxVerb'], 1, 0, @_)
		if ($XPRED eq 'DEFAULT');
	die "Unknown value \'$XPRED\' for global constant \$XPRED ";
	# Root is labeled according to settings, if baseElem in root.
}
sub xApp
{
	return &_chainAll(0, $LABEL_SUBROOT, @_);
	# Root is labeled according to settings.
}

sub namedEnt
{
	my $xpc = shift @_; # XPath context
	my $node = shift @_;
	my $parentRole = shift @_;
	my $warnFile = shift @_;

	# Find the new root ('subroot') for the current subtree.
	my @ch = $xpc->findnodes('pml:children/pml:node', $node);
	die 'namedEnt below '. $node->find('../../@id').' has no children!'
		if (@ch lt 1);

	if (@ch gt 1)
	{
		return &_defaultTransform(
			$LABEL_SUBROOT, $xpc, $node, $parentRole, $warnFile);
		# Root is labeled according to settings.
	}
	else	
	{
		# According to current methodology 1-child named entities is not used.
		print "namedEnt has 1 child.\n";
		print $warnFile "namedEnt below ". $node->find('../../@id')
			." has 1 child.\n";
		# Change role for the subroot.
		&_renamePhraseSubroot(
			$LABEL_SUBROOT, $xpc, $ch[0], $parentRole, 'namedEnt');
		$ch[0]->unbindNode();
		return $ch[0];
		# Root is labeled according to settings.
	}
}

sub phrasElem
{
	my $xpc = shift @_; # XPath context
	my $node = shift @_;
	my $parentRole = shift @_;
	my $warnFile = shift @_;
	
	# Warning about deprecated constuction.
	print "Deprecated constuction: phrasElem.\n";
	print $warnFile 'phrasElem below '. $node->find('../../@id').".\n";

	# Find the new root ('subroot') for the current subtree.
	my @ch = $xpc->findnodes('pml:children/pml:node', $node);
	die 'phrasElem below '. $node->find('../../@id').' has no children!'
		if (@ch lt 1);

	if (@ch gt 1)
	{
		# Warning about suspective structure.
		print 'phrasElem has '.(scalar @ch)." children.\n";
		print $warnFile 'phrasElem below '. $node->find('../../@id').' has '
			.(scalar @ch)." children.\n";
		return &_defaultTransform(
			$LABEL_SUBROOT, $xpc, $node, $parentRole, $warnFile);
		# Root is labeled according to settings.
	}
	else
	{
		# Change role for the subroot.
		&_renamePhraseSubroot(
			$LABEL_SUBROOT, $xpc, $ch[0], $parentRole, 'phrasElem');
		$ch[0]->unbindNode();
		return $ch[0];
		# Root is labeled according to settings.
	}
}
sub unstruct
{
	return &_defaultTransform($LABEL_SUBROOT, @_);
	# Root is labeled according to settings.
}


### Coordination ##############################################################

sub crdParts
{
	return &_chainStartingFrom(['crdPart', 'gen'], $LABEL_SUBROOT, @_)
		if ($COORD eq 'ROW');
	return &_chainSpecified(['crdPart', 'gen'], $LABEL_SUBROOT, @_)
		if ($COORD eq 'ROW_NO_CONJ');
	return &_allBelowCoordSep(1, @_) if ($COORD eq 'DEFAULT');
	die "Unknown value \'$COORD\' for global constant \$COORD ";
	# Root is labeled according to settings if elements are chained.
}
sub crdClauses
{
	return &_chainStartingFrom(['crdPart', 'gen'], $LABEL_SUBROOT, @_)
		if ($COORD eq 'ROW');
	return &_chainSpecified(['crdPart', 'gen'], $LABEL_SUBROOT, @_)
		if ($COORD eq 'ROW_NO_CONJ');
	return &_allBelowCoordSep(1, @_) if ($COORD eq 'DEFAULT');
	die "Unknown value \'$COORD\' for global constant \$COORD ";
	# Root is labeled according to settings if elements are chained.
}
sub crdGeneral
{
	return &_chainStartingFrom(['crdPart', 'gen'], $LABEL_SUBROOT, @_)
		if ($COORD eq 'ROW');
	return &_chainSpecified(['crdPart', 'gen'], $LABEL_SUBROOT, @_)
		if ($COORD eq 'ROW_NO_CONJ');
	return &_allBelowCoordSep(1, @_) if ($COORD eq 'DEFAULT');
	die "Unknown value \'$COORD\' for global constant \$COORD ";
	# Root is labeled according to settings if elements are chained.
}

### PMC #######################################################################

sub sent
{
	return &_allBelowPmcBase(1, @_) if ($PMC eq 'BASELEM');
	return &_allBelowPunct(1, 0, 1, @_) if ($PMC eq 'DEFAULT');
	die "Unknown value \'$PMC\' for global constant \$PMC ";
	# Root is labeled always.
}
sub mainCl
{
	return &_allBelowPmcBase(1, @_) if ($PMC eq 'BASELEM');
	return &_allBelowPunct(0, 0, 1, @_) if ($PMC eq 'DEFAULT');
	die "Unknown value \'$PMC\' for global constant \$PMC ";
	# Root is labeled always.
}
sub subrCl
{
	return &_allBelowPmcBase(1, @_) if ($PMC eq 'BASELEM');
	return &_allBelowPunct(0, 0, 1, @_) if ($PMC eq 'DEFAULT');
	die "Unknown value \'$PMC\' for global constant \$PMC ";
	# Root is labeled always.
}
sub interj
{
	return &_allBelowPmcBase(1, @_) if ($PMC eq 'BASELEM');
	return &_allBelowPunct(0, 0, 1, @_) if ($PMC eq 'DEFAULT');
	die "Unknown value \'$PMC\' for global constant \$PMC ";
	# Root is labeled always.
}
sub spcPmc
{
	return &_allBelowPmcBase($LABEL_SUBROOT, @_) if ($PMC eq 'BASELEM');
	return &_allBelowPunct(0, 0, 1, @_) if ($PMC eq 'DEFAULT');
	die "Unknown value \'$PMC\' for global constant \$PMC ";
	# Root is labeled according to settings, if baseElem in root.
}
sub insPmc
{
	return &_allBelowPmcBase(1, @_) if ($PMC eq 'BASELEM');
	return &_allBelowPunct(0, 0, 1, @_) if ($PMC eq 'DEFAULT');
	die "Unknown value \'$PMC\' for global constant \$PMC ";
	# Root is labeled always.
}
sub particle
{
	return &_allBelowPmcBase(1, @_) if ($PMC eq 'BASELEM');
	return &_allBelowPunct(0, 0, 1, @_) if ($PMC eq 'DEFAULT');
	die "Unknown value \'$PMC\' for global constant \$PMC ";
	# Root is labeled always.
}
sub dirSpPmc
{
	return &_allBelowPmcBase(1, @_) if ($PMC eq 'BASELEM');
	return &_allBelowPunct(0, 0, 1, @_) if ($PMC eq 'DEFAULT');
	die "Unknown value \'$PMC\' for global constant \$PMC ";
	# Root is labeled always.
}
sub address
{
	return &_allBelowPmcBase(1, @_) if ($PMC eq 'BASELEM');
	return &_allBelowPunct(0, 0, 1, @_) if ($PMC eq 'DEFAULT');
	die "Unknown value \'$PMC\' for global constant \$PMC ";
	# Root is labeled always.
}
sub quot
{
	return &_allBelowPmcBase($LABEL_SUBROOT, @_) if ($PMC eq 'BASELEM');
	return &_allBelowPunct(0, 0, 1, @_) if ($PMC eq 'DEFAULT');
	die "Unknown value \'$PMC\' for global constant \$PMC ";
	# Root is labeled according to settings, if baseElem in root.
}

sub utter
{
	if ($PMC eq 'BASELEM')
	{
		my $xpc = shift @_; # XPath context
		my $node = shift @_;
		my $parentRole = shift @_;
		my $warnFile = shift @_;
		my $phraseRole = getRole($xpc, $node);
		
		# Find the new root ('subroot') for the current subtree.
		my @res = $xpc->findnodes(
			"pml:children/pml:node[pml:role!=\'no\' and pml:role!=\'punct\' and pml:role!=\'conj\']",
			$node);
		@res = $xpc->findnodes(
			"pml:children/pml:node[pml:role!=\'punct\' and pml:role!=\'conj\']", $node)
			unless (@res);
		@res = $xpc->findnodes(
			"pml:children/pml:node[pml:role!=\'punct\']", $node) unless (@res);
		die "utter below ". $node->find('../../@id').' has no children!'
			if (not @res);
		# Warning about suspective structure.
		if (scalar @res ne 1)
		{	
			print "$phraseRole has ".(scalar @res)." potential rootnodes.\n";
			print $warnFile "$phraseRole below ". $node->find('../../@id').' has '
				.(scalar @res)." potential rootnodes.\n";
		}
		
		my $newRoot = $res[0];
		
		# Rebuild subtree.
		$newRoot = &_finshPhraseTransf(1, $xpc, $node, $newRoot, $parentRole);
		return $newRoot;
	}
	return &_allBelowPunct(1, 1, 1, @_) if ($PMC eq 'DEFAULT');
	die "Unknown value \'$PMC\' for global constant \$PMC ";
	# Root is labeled always.
}


### What to do when don't know what to do #####################################
# AUTOLOAD is called when someone tries to access nonexisting method through
# this package. See perl documentation.
# This is used for handling unknown PMC/X-word/coordination types.
sub AUTOLOAD
{
	our $AUTOLOAD;
	my $name = $AUTOLOAD;
	$name =~ s/.*::(.*?)$/$1/;
	my $warnFile = $_[3];
	
	print "Don't know how to process \"$name\", will use default rule.\n";
	print $warnFile "Don't know how to process \"$name\" below "
		.$_[1]->find('../../@id').", will use default rule.\n";
	return &_defaultTransform(1, @_);
}

###############################################################################
# Aditional functions for phrase handling
###############################################################################

# Put everrything below last basElem or last constituent.
# _defaultTransform (relabel new root (0/1; if $LABEL_SUBROOT=1, this will be
#					 ignored and root will be renamed anyway), XPath context
#					 with set namespaces, DOM node, role of the parent node,
#					 output flow for warnings)
sub _defaultTransform
{
	my $labelNewRoot = shift @_;
	my $xpc = shift @_; # XPath context
	my $node = shift @_;
	my $parentRole = shift @_;
	# my $warnFile = shift @_; # Not used right now
	my $phraseRole = getRole($xpc, $node);
	
	# Find the new root ('subroot') for the current subtree.
	my @basElems = $xpc->findnodes(
		'pml:children/pml:node[pml:role=\'basElem\']', $node);
	my $lastBasElem = undef;
	my $curentPosition = -1;
	foreach my $ch (@basElems)	# Find a basElem with the greatest 'ord'.
	{
		my $tmpOrd = ${$xpc->findnodes('pml:ord', $ch)}[0]->textContent;
		if ($tmpOrd ge $curentPosition)
		{
			$lastBasElem = $ch;
			$curentPosition = $tmpOrd;
		}
	}
	if (not defined $lastBasElem) # If this phrase contains no basElem, use last constituent.
	{
		my @children = $xpc->findnodes('pml:children/pml:node', $node);
		$lastBasElem = $children[-1];
	}

	# Rebuild subtree.
	$lastBasElem = &_finshPhraseTransf(($labelNewRoot or $LABEL_SUBROOT), $xpc,
		$node, $lastBasElem, $parentRole);
	return $lastBasElem;
}

### ...for X-words ############################################################

# Finds child element with specified role and makes ir parent of other children
# nodes.
# _allBelowOne (pointer to array with roles determining node to become root,
#				relabel new root (0/1; if $LABEL_SUBROOT=1, this will be
#				ignored and root will be renamed anyway),  warn if multiple
#				potential roots (0/1), XPath context with set namespaces, DOM
#				node, role of the parent node, output flow for warnings)
sub _allBelowOne
{
	my $rootRoles = shift @_;
	my $labelNewRoot = shift @_;
	my $warn = shift @_;
	my $xpc = shift @_; # XPath context
	my $node = shift @_;
	my $parentRole = shift @_;
	my $warnFile = shift @_;
	my $phraseRole = getRole($xpc, $node);
	
	# Find node with speciffied rootRoles.
	my $query = join '\' or pml:role=\'', @$rootRoles;
	$query = "pml:children/pml:node[pml:role=\'$query\']";
	my @res = $xpc->findnodes($query, $node);
	if (not @res)
	{
		my $roles = join '/', @$rootRoles;
		die "$phraseRole below ". $node->find('../../@id')
			." have no $roles children!"
	}
	if (scalar @res ne 1 and $warn)
	{
		my $roles = join '/', @$rootRoles;
		print "$phraseRole has ".(scalar @res)." potential rootnodes.\n";
		print $warnFile "$phraseRole below ". $node->find('../../@id').' has '
			.(scalar @res)." potential rootnodes $roles.\n";
	}
	
	my @sorted = @{sortNodesByOrd($xpc, 0, @res)};
	my $newRoot = $sorted[0];
	
	# Rebuild subtree.
	$newRoot = &_finshPhraseTransf(
		$labelNewRoot, $xpc, $node, $newRoot, $parentRole);
	return $newRoot;
}

# Makes parent-child chain of all node's children.
# _chainAll (should start with last node, relabel new root (0/1; if
#			 $LABEL_SUBROOT=1, this will be ignored and root will be renamed
#			 anyway), XPath context with set namespaces, DOM node, role of the
#			 parent node, output flow for warnings)
sub _chainAll
{
	my $invert = shift @_;
	my $labelNewRoot = shift @_;
	my $xpc = shift @_; # XPath context
	my $node = shift @_;
	my $parentRole = shift @_;
	# my $warnFile = shift @_; # Not used right now
	my $phraseRole = getRole($xpc, $node);

	# Find the children.
	my @ch = $xpc->findnodes('pml:children/pml:node', $node);
	die "$phraseRole below ". $node->find('../../@id').' has no children!'
		if (@ch lt 1);
	my @sorted = @{sortNodesByOrd($xpc, $invert, @ch)};
	my $newRoot = $sorted[0];
	my $tmpRoot = $sorted[0];
	
	# Process new root.
	$newRoot->unbindNode();
	&_renamePhraseSubroot(
		$labelNewRoot, $xpc, $newRoot, $parentRole, $phraseRole);
	
	# Process other nodes.
	for (my $ind = 1; $ind lt @sorted; $ind++)
	{
		# Move to new parent.
		&_movePhraseChild($sorted[$ind], $tmpRoot, $phraseRole, $xpc);
		$tmpRoot = $sorted[$ind];
	}
	return $newRoot;
}

### ...for coordination #######################################################

# Finds child element with specified role and makes ir parent of other children
# nodes before given element. All children nodes after that element are
# combined into parent-child chain.
# _chainStartingFrom (pointer to array with roles determining node to become
#					  root, relabel new root (0/1; if $LABEL_SUBROOT=1, this
#					  will be ignored and root will be renamed anyway), XPath
#					  context with set namespaces, DOM node, role of the parent
#					  node, output flow for warnings)
sub _chainStartingFrom
{
	my $rootRoles = shift @_;
	my $labelNewRoot = shift @_;
	my $xpc = shift @_; # XPath context
	my $node = shift @_;
	my $parentRole = shift @_;
	my $warnFile = shift @_; #
	my $phraseRole = getRole($xpc, $node);
	
	# Find node with speciffied rootRoles.
	my $query = join '\' or pml:role=\'', @$rootRoles;
	$query = "pml:children/pml:node[pml:role=\'$query\']";
	my @res = $xpc->findnodes($query, $node);
	if (not @res)
	{
		my $roles = join '/', @$rootRoles;
		print "$phraseRole have no $roles children.\n";
		print $warnFile "$phraseRole below ". $node->find('../../@id')
			." have no $roles children.\n";
		return &_chainAll(0, $labelNewRoot, $xpc, $node, $parentRole, $warnFile);
	}
	# In case of multiple roots choose first.
	@res = @{sortNodesByOrd($xpc, 0, @res)};
	
	# Root for children before $newRoot.
	my $newRoot = $res[0];
	# Ever-changing root for children after $newRoot.
	my $newSubRoot = $res[0];
	
	my $rootOrd = getOrd($xpc, $newRoot);

	# Process new root.
	$newRoot->unbindNode();
	&_renamePhraseSubroot(
		$labelNewRoot, $xpc, $newRoot, $parentRole, $phraseRole);
	
	# Find the children.
	my @children = $xpc->findnodes('pml:children/pml:node', $node);
	die "$phraseRole below ". $node->find('../../@id').' has les than 2 children!'
		if (@children lt 1);
	@children = @{sortNodesByOrd($xpc, 0, @children)};
	
	for my $ch (@children)
	{
		my $chOrd = getOrd($xpc, $ch);
		if ($chOrd < $rootOrd)
		{
			# Move to new parent - $newRoot.
			&_movePhraseChild($ch, $newRoot, $phraseRole, $xpc);
		}
		else
		{
			# Move to new parent - $newSubRoot and then reset $newSubRoot.
			&_movePhraseChild($ch, $newSubRoot, $phraseRole, $xpc);
			$newSubRoot = $ch;
		}
	}
	
	return $newRoot;
}
# Chain child elements with specified roles. Unchained child elements attach
# to next chained element.
# _chainSpecified (pointer to array with roles determining node to be chained
#				   relabel new root (0/1; if $LABEL_SUBROOT=1, this will be
#				   ignored and root will be renamed anyway), XPath context with
#				   set namespaces, DOM node, role of the parent node, output
#				   flow for warnings)
sub _chainSpecified
{
	my $chainRoles = shift @_;
	my $labelNewRoot = shift @_;
	my $xpc = shift @_; # XPath context
	my $node = shift @_;
	my $parentRole = shift @_;
	my $warnFile = shift @_; #
	my $phraseRole = getRole($xpc, $node);
	
	# Find nodes to be chained.
	my $chQuery = join '\' or pml:role=\'', @$chainRoles;
	$chQuery = "pml:children/pml:node[pml:role=\'$chQuery\']";
	my @forChain = $xpc->findnodes($chQuery, $node);
	if (not @forChain)
	{
		my $roles = join '/', @$chainRoles;
		print "$phraseRole have no $roles children.\n";
		print $warnFile "$phraseRole below ". $node->find('../../@id')
			." have no $roles children.\n";
		return &_chainAll(0, $labelNewRoot, $xpc, $node, $parentRole, $warnFile);
	}
	# Sort roles to be chained to find the new root.
	@forChain = @{sortNodesByOrd($xpc, 0, @forChain)};
	my $newRoot = $forChain[0];
	# Process new root.
	$newRoot->unbindNode();
	&_renamePhraseSubroot(
		$labelNewRoot, $xpc, $newRoot, $parentRole, $phraseRole);
	
	# Find other children.
	my $othQuery = join '\' and pml:role!=\'', @$chainRoles;
	$othQuery = "pml:children/pml:node[pml:role!=\'$othQuery\']";
	my @other = $xpc->findnodes($othQuery, $node);
	@other = @{sortNodesByOrd($xpc, 0, @other)};
	
	# Process first chain element.
	my $newSubRoot = shift @forChain;
	my $subRootOrd = getOrd($xpc, $newSubRoot);
	# Move first conj/punct under this chain element.
	while (@other and (getOrd($xpc, $other[0]) <= $subRootOrd))
	{
		my $toBeMoved = shift @other;
		&_movePhraseChild($toBeMoved, $newRoot, $phraseRole, $xpc);
	}

	# Process the rest of chain elements.	
	for my $ch (@forChain)
	{
		# Move conj and punct under this current chain element
		my $chOrd = getOrd($xpc, $ch);
		while (@other and (getOrd($xpc, $other[0]) <= $chOrd))
		{
			my $toBeMoved = shift @other;
			&_movePhraseChild($toBeMoved, $ch, $phraseRole, $xpc);
		}
		
		# Move chain element.
		&_movePhraseChild($ch, $newSubRoot, $phraseRole, $xpc);
		$newSubRoot = $ch;
	}

	return $newRoot;
}

# Put everything below first conjuction or punctuation. Excetpion: if
# punctuation is followed by conjunction with no other elements between those
# two, conjunction is used as root.
# _allBelowCoordSep (relabel new root (0/1; if $LABEL_SUBROOT=1, this will be
#					 ignored and root will be renamed anyway), XPath context
#					 with set namespaces, DOM node, role of the parent node,
#					 output flow for warnings)
sub _allBelowCoordSep
{
	my $labelNewRoot = shift @_;
	my $xpc = shift @_; # XPath context
	my $node = shift @_;
	my $parentRole = shift @_;
	my $warnFile = shift @_;
	my $phraseRole = getRole($xpc, $node);

	my @ch = $xpc->findnodes('pml:children/pml:node', $node);
	my @sorted = @{sortNodesByOrd($xpc, 0, @ch)};
	
	die "$phraseRole below ". $node->find('../../@id').' has no children!'
		if (not @sorted);
	# Warning about suspective structure.
	if (scalar @sorted < 3)
	{
		print "$phraseRole has left only ".(scalar @sorted)." children.\n";
		print $warnFile "$phraseRole below ". $node->find('../../@id')
			.' has left only '.(scalar @sorted)." children.\n";
	}
	my $firstRole = getRole($xpc, $sorted[0]);
	# Warning about suspective structure.
	if ($firstRole eq 'punct')
	{
		print "$phraseRole starts with $firstRole.\n";
		print $warnFile "$phraseRole below ". $node->find('../../@id')
			." starts with $firstRole.\n";
	}
	
	# Find the new root node.
	my $newRoot = undef;
	for my $ind (0..$#sorted)
	{
		my $role = getRole($xpc, $sorted[$ind]);
		if ($role eq 'conj')
		{
			$newRoot = $sorted[$ind];
			last;
		}
		elsif ($role eq 'punct')
		{
			if ($ind < $#sorted and  getRole($xpc, $sorted[$ind+1]) eq 'conj')
			{
				$newRoot = $sorted[$ind+1];
			}
			else
			{
				$newRoot = $sorted[$ind];
			}
			last;
		}
	}
	
	# If this coordination contained no node appropriate to be coordination
	# head ("punct" or "conj"), it is analized as coordination anague
	my @validRootRoles = $xpc->findnodes(
		'pml:children/pml:node[pml:role=\'conj\' or pml:role=\'punct\']', $node);
	if (not defined $newRoot)
	{
		# Warning about suspective structure.
		print "$phraseRole contains not enough conj and punct.\n";
		print $warnFile "$phraseRole below ". $node->find('../../@id')
			." contains not enough conj and punct.\n";
		return  &coordAnal ($xpc, $node, $parentRole, $warnFile);
	}
	
	# Rebuild subtree.
	$newRoot = &_finshPhraseTransf(
		$labelNewRoot, $xpc, $node, $newRoot, $parentRole);
	return $newRoot;
}

### ...for PMC ################################################################
# Put everything below first conjuction, if there is no conjunctions, then
# below first/last (1st param) punctuatuation, if no punctuation, then below 
# first basElem/no/etc (using 'no' for root is regulated by 2nd param). 
# _allBelowPunct (should start with last 'punct', treat 'no' as 'basElem',
#				  relabel new root (0/1; if $LABEL_SUBROOT=1, this will be
#				  ignored and root will be renamed anyway), XPath context with
#				  set namespaces, DOM node, role of the parent node, output
#				  flow for warnings)
sub _allBelowPunct
{
	my $invert = shift @_;
	my $useNo = shift @_;
	my $labelNewRoot = shift @_;	
	my $xpc = shift @_; # XPath context
	my $node = shift @_;
	my $parentRole = shift @_;
	my $warnFile = shift @_;
	my $phraseRole = getRole($xpc, $node);
	
	# Find the new root ('subroot') for the current subtree.
	my @ch = $xpc->findnodes("pml:children/pml:node[pml:role=\'conj\']", $node);
	my @res = @{sortNodesByOrd($xpc, 0, @ch)};
	if (not @ch)
	{
		@ch = $xpc->findnodes("pml:children/pml:node[pml:role=\'punct\']", $node);
		@res = @{sortNodesByOrd($xpc, $invert, @ch)};
	}
	if (not @ch)
	{
		my $searchString = $useNo ?
			'pml:children/pml:node' :
			'pml:children/pml:node[pml:role!=\'no\']';
		@ch = $xpc->findnodes($searchString, $node);
		# Warning about suspective structure.
		if (scalar @ch ne 1)
		{
			print "$phraseRole has ".(scalar @ch)
				." potential non-punct/conj/no rootnodes.\n";
			print $warnFile "$phraseRole below ". $node->find('../../@id').' has '
				.(scalar @ch)." potential non-punct/conj/no rootnodes.\n";
		}
		@res = @{sortNodesByOrd($xpc, 0, @ch)};	
	}
	die "$phraseRole below ". $node->find('../../@id').' has no children!'
		if (not @ch);
	my $newRoot = $res[0];
	
	# Rebuild subtree.
	$newRoot = &_finshPhraseTransf(
		($labelNewRoot or $LABEL_SUBROOT), $xpc, $node, $newRoot, $parentRole);
	return $newRoot;
}
# _allBelowPmcBase (relabel new root (0/1; if $LABEL_SUBROOT=1, this will be
#					ignored and root will be renamed anyway), XPath context
#					with set namespaces, DOM node, role of the parent node,
#					output flow for warnings)
sub _allBelowPmcBase
{
	my $labelNewRoot = shift @_;	
	my $xpc = shift @_; # XPath context
	my $node = shift @_;
	my $parentRole = shift @_;
	my $warnFile = shift @_;
	my $phraseRole = getRole($xpc, $node);
	
	# Find the new root ('subroot') for the current subtree.
	my @ch = $xpc->findnodes(
		"pml:children/pml:node[pml:role!=\'no\' and pml:role!=\'punct\' and pml:role!=\'conj\']",
		$node);
	my @res = @{sortNodesByOrd($xpc, 0, @ch)};
	die "$phraseRole below ". $node->find('../../@id')
		.' have only no/punct/conj children!'
		if (not @res);
	# Warning about suspective structure.
	if (scalar @res ne 1)
	{
		print "$phraseRole has ".(scalar @res)." potential rootnodes.\n";
		print $warnFile "$phraseRole below ". $node->find('../../@id').' has '
			.(scalar @res)." potential rootnodes.\n";
	}
	
	my $newRoot = $res[0];
	
	# Rebuild subtree.
	$newRoot = &_finshPhraseTransf(
		($labelNewRoot or $LABEL_SUBROOT), $xpc, $node, $newRoot, $parentRole);
	return $newRoot;
}

###############################################################################
# Techsupport functions
###############################################################################

# Final step in handling phrese node (PMC/coordination/x-word) - move old
# parent children to new parent, change roles for children and new parent.
# _finshPhraseTransf (relabel new root (0/1; if $LABEL_SUBROOT=1, this will be
#					  ignored and root will be renamed anyway), XPath context
#					  with set namespaces, old parent of children to be moved,
#					  new parent, role of the old parent's parent node)
sub _finshPhraseTransf
{
	my $labelNewRoot = shift @_;
	my $xpc = shift @_;
	my $oldRoot = shift @_;
	my $newRoot = shift @_;
	my $parentRole = shift @_;
	#my $phraseRole = shift @_;
	my $phraseRole = getRole($xpc, $oldRoot);
	
	# Change role for node with speciffied rootRole.
	&_renamePhraseSubroot(
		$labelNewRoot, $xpc, $newRoot, $parentRole, $phraseRole);

	# Rebuild subtree.
	$newRoot->unbindNode();
	foreach my $ch ($xpc->findnodes('pml:children/pml:node', $oldRoot))
	{
		&_renamePhraseChild($xpc, $ch, $phraseRole);
	}
	moveChildren($xpc, $oldRoot, $newRoot);

	return $newRoot;
}

# Move phrase children (type: node) to new parent (type: supposed to be node,
# but nothing should break if type is xinfo/coordinfo/pmcinfo). This includes
# relabeling with &getChildrenNode.
# movePhraseChild (node to be moved, new parent, label of
#				   x-word/coordination/pmc this node is part of, XPath context
#				   with set namespaces)
sub _movePhraseChild
{
	my $node = shift @_;
	my $newParent = shift @_;
	my $phraseRole = shift @_;
	my $xpc = shift @_;

		$node->unbindNode();
		&_renamePhraseChild($xpc, $node, $phraseRole);
		my $chNode = getChildrenNode($xpc, $newParent);
		$chNode->appendChild($node);
}

1;