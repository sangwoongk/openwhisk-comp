#!/bin/bash

ansible-playbook -i environments/local couchdb.yml -e mode=clean
ansible-playbook -i environments/local openwhisk.yml -e mode=clean
ansible-playbook -i environments/local apigateway.yml -e mode=clean
