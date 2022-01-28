#!/bin/bash
# it's important that these auto-generated shapes be named the same as their corresponding classes otherwise an explicit sh:targetClass property
# is required
if [ -d ./autoshapes ]; then
	find ./autoshapes/ -type f -exec rm {} \;
	for f in `cat linkedArtReducedShapes.ttl | grep sh:NodeShape | cut -d ' ' -f 1`; do ../scripts/copyshape.pl $f ./autoshapes/; done;
else
	echo "script should be run from the shacl-validation/autoshapes folder"
fi
