#! /bin/sh


cd option-trading/dev
INSTALL_DIR=`pwd`

if [ -z $MODULE_NAME ]
then
  echo "Missing module name"
  exit 1
fi

if [ -z ${MAIN_JAR} ]
then
  echo "Missing main jar name"
  exit 1
fi

if [ -z $MODULE_INSTANCE ]
then
  echo "Module instance is not specified, default to 1"
  MODULE_INSTANCE=1
fi

if [ -z ${MAIN_CLASS} ]
then
  MAIN_CLASS="awesome.code.launcher.GRPCServiceLauncher"
  echo "MAIN_CLASS is not set, default to $MAIN_CLASS"
else
  echo "Java main class is explicitly defined: $MAIN_CLASS"
fi

CONFIG_DIR="config"
CLASS_PATH="lib/*"

echo "${CLASS_PATH}"

echo "information env: $RUN_ENV, module: $MODULE_NAME, module instance: $MODULE_INSTANCE, run dir: $RUN_DIR, install dir: INSTALL_DIR"
CORE_COMMANDLINE="-e $RUN_ENV -c ${CONFIG_DIR} -svc $MODULE_NAME -svi $MODULE_INSTANCE"
CMD="java -classpath $MAIN_JAR:$CLASS_PATH $MAIN_CLASS $CORE_COMMANDLINE"

echo "${CMD}"
${CMD}
