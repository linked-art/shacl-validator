#!/bin/perl
use strict;
use File::Slurp;

my $filename = "./linkedArtReducedShapes.ttl";

my @lines = read_file($filename); # one line per element

my $class=$ARGV[0];
my $path=$ARGV[1];

# eliminate the reserved namespace prefix before writing the class file
my $classfilename=$class;
$classfilename =~ s/(.*)\://g;
$classfilename = $path."/".$classfilename.".ttl";

my $printLine=0;
open(my $f, ">", $classfilename);
foreach my $line (@lines) {
	print $f $line if $line =~ m/\@prefix/;
	if ( $line =~ m/^$class\s/ ) {
		print$f  "\n";
		$printLine = 1;
	}
	exit if $printLine && $line =~ m/^\s*$/;
	print $f $line if $printLine;
}
