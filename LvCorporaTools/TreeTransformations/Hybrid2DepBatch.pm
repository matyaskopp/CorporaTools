#!C:\strawberry\perl\bin\perl -w
package LvCorporaTools::TreeTransformations::Hybrid2DepBatch;

use strict;
use warnings;

use Exporter();
our @ISA = qw(Exporter);
our @EXPORT_OK = qw(transformFileBatch);

use LvCorporaTools::TreeTransformations::Hybrid2Dep qw(transformFile);

use IO::File;
use IO::Dir;

###############################################################################
# Batch processing for LvCorporaTools::TreeTransformations::Hybrid2Dep - if
# single argument provided, treat it as directory and process all files in it.
# Otherwise pass all arguments to Hybrid2Dep.
#
# Developed on Strawberry Perl 5.12.3.0
# Latvian Treebank project, 2012
# Lauma Pretkalnina, LUMII, AILab, lauma@ailab.lv
# Licenced under GPL.
###############################################################################
sub transformFileBatch
{
	if (@ARGV eq 1)
	{

		my $dir_name = $ARGV[0];
		my $dir = IO::Dir->new($dir_name) or die "dir $!";

		while (defined(my $in_file = $dir->read))
		{
			if ((! -d "$dir_name/$in_file") and ($in_file =~ /^(.+)\.a$/))
			{
				transformFile ($dir_name, $in_file, "$1-dep.a");
			}
		}

	}
	else
	{
		transformFile (@ARGV);
	}
}
1;