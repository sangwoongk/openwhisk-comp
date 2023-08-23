#!/bin/bash

ansible-playbook -i environments/local setup.yml
ansible-playbook -i environments/local couchdb.yml
ansible-playbook -i environments/local initdb.yml
ansible-playbook -i environments/local wipe.yml
ansible-playbook -i environments/local openwhisk.yml -e skip_pull_runtimes=true
ansible-playbook -i environments/local apigateway.yml
ansible-playbook -i environments/local routemgmt.yml
