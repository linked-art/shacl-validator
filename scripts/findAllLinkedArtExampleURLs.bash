#!/bin/bash

# look for example data URIs on the linked art site
for entity in activity concept digital event group object person place provenance set text visual; do
	for id in {0..100}; do
		url="https://linked.art/example/$entity/$id.json"
		status=`curl -s -I -o /dev/null -w "%{http_code}" $url`
		if [ $status -eq 200 ]; then
			echo $url 
		fi
	done
done
