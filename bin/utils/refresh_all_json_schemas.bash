#!/bin/bash

for entity in core digital event group image object person place provenance set text; do
        echo $entity;
        curl -o ./${entity}.json "https://raw.githubusercontent.com/linked-art/json-validator/master/schema/${entity}.json"
done;
