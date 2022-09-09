#!/bin/bash
for it in {0..800};
do
	for i in {0..3};
	do
		num=`printf "%.3d" $i`
		wsk -i action invoke func$num
	done
	if [ $it -eq 0 ]
	then
		sleep 10
	else
		sleep 0.5
	fi
done
