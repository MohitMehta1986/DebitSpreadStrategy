#! /bin/sh

SCRIPT=$(readlink -f "$0")
SCRIPT_BASE=`dirname "$SCRIPT"`
echo  SCRIPT
echo  SCRIPT_BASE

export MODULE_NAME=OptionLoaderJob
export MODULE_INSTANCE=1
export VM_ARGS="-Xms4g -Xmx8g -XX:MaxDirectMemorySize=750M"
export MAIN_CLASS=awesome.code.service.impl.StandaloneServiceLauncher
export MAIN_JAR="option-loader-1.0.0-SNAPSHOT.jar"


export cp_file=option-loader-classpath-unix.txt

$SCRIPT_BASE/k8s-start-main.sh --servicename $MODULE_NAME --serviceinstance $MODULE_INSTANCE