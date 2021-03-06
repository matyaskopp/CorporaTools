REM This is how you transform to dependencies for syntax experiments.
perl -e "use LvCorporaTools::TreeTransf::Hybrid2Dep qw($XPRED $COORD $PMC $LABEL_ROOT transformFile); $COORD = 'DEFAULT'; $PMC = 'DEFAULT'; $XPRED = 'DEFAULT_NO_RED'; $LABEL_ROOT = 0; transformFile(@ARGV)" testdata\Hybrid2Dep zeens.a zeens-synt.a

REM This is how you transform to dependencies for semantic experiments.
perl -e "use LvCorporaTools::TreeTransf::Hybrid2Dep qw($XPRED $COORD $PMC $LABEL_ROOT transformFile); $COORD = 'ROW'; $PMC = 'BASELEM'; $XPRED = 'BASELEM_NO_RED'; $LABEL_ROOT = 0; transformFile(@ARGV)" testdata\Hybrid2Dep zeens.a zeens-sem.a

REM This ir how you perform batch transformations.
perl -e "use LvCorporaTools::TreeTransf::Hybrid2Dep qw(processDir); processDir(@ARGV)" testdata\Hybrid2Dep

REM This is how you mark xpred constituents.
perl -e "use LvCorporaTools::TreeTransf::Hybrid2Dep qw($MARK transformFile); $MARK = {'sent'=>1, 'utter'=>1, 'mainCl'=>1, 'subrCl'=>1, 'interj'=>1, 'spcPmc'=>1, 'insPmc'=>1, 'particle'=>1, 'dirSpPmc'=>1, 'address'=>1, 'quot'=>1}; transformFile(@ARGV)" testdata\Hybrid2Dep zeens.a zeens-mark.a

REM This is how you mark xpred phrase dependents.
perl -e "use LvCorporaTools::TreeTransf::Hybrid2Dep qw($MARK_PHDEP transformFile); $MARK_PHDEP = {'sent'=>1, 'utter'=>1, 'mainCl'=>1, 'subrCl'=>1, 'interj'=>1, 'spcPmc'=>1, 'insPmc'=>1, 'particle'=>1, 'dirSpPmc'=>1, 'address'=>1, 'quot'=>1}; transformFile(@ARGV)" testdata\Hybrid2Dep zeens.a zeens-markPhdep.a

pause