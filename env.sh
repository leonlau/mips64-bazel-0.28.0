
export PROTOC=/usr/local/bin/protoc
export JAVA_HOME=/opt/j2sdk-image
export JRE_HOME=${JAVA_HOME}/jre
export CLASSPATH=.:${JAVA_HOME}/lib:${JRE_HOME}/lib
export PATH=${JAVA_HOME}/bin:/usr/local/bin/:$PATH
export JAVA_OPTS="-server -Xms20g -Xmx20g"
export TMPDIR=/tmp/bazel_tmp_output

