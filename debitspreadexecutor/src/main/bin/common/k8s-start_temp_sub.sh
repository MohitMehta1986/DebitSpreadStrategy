#! /bin/sh
cd option-loader/dev
INSTALL_DIR=`pwd`
JOB_NAME="OptionLoaderJob"
MAIN_CLASS="SubscribeRunner"
MAIN_JAR="option-loader-1.0.0-SNAPSHOT.jar"
LIB_DIR=$INSTALL_DIR/lib
echo "${INSTALL_DIR}"
CLASS_PATH="lib/*"
echo "${CLASS_PATH}"
LS_CMD="ls -ltr"
${LS_CMD}

CMD="java -classpath $MAIN_JAR:$CLASS_PATH $MAIN_CLASS"

echo "${CMD}"
${CMD}