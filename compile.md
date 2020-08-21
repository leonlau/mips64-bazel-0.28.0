### 编译 bazel

####  编译protoc

```
git clone https://github.com/google/protobuf.git

./autogen.sh
./configure
make
make check
sudo make install
sudo ldconfig # refresh shared library cache.

```



#### 编译 bazel

github  下载`bazel-0.21.0-dist.zip` 解压 


安装 openjdk-8-jdk

>  一定要用龙芯优化后的openjdk,  一定!

[龙芯JDK下载地址](http://www.loongnix.org/index.php/Java)

```
wget http://ftp.loongnix.org/toolchain/java/openjdk8/loongson_openjdk8.1.4-jdk8u242b08-linux-loongson3a.tar.gz
tar xvf -O /opt loongson_openjdk8.1.4-jdk8u242b08-linux-loongson3a.tar.gz 
#解压到 /opt 目录后
指定 JAVA_HOME 

```

安装 编译环境

python 2 和3 版本都行, 

```bash
apt-get install -y zip zip unzip build-essential python2 gcc
ln -s /usr/bin/python2 /usr/bin/python
```
编译
``` bash
#设置环境变量 
export PROTOC=/usr/local/bin/protoc
export JAVA_HOME=/opt/j2sdk-image
export JRE_HOME=${JAVA_HOME}/jre
export CLASSPATH=.:${JAVA_HOME}/lib:${JRE_HOME}/lib
export PATH=${JAVA_HOME}/bin:/usr/local/bin/:$PATH
export JAVA_OPTS="-server -Xms20g -Xmx20g"
export TMPDIR=/tmp/bazel_tmp_output

env EXTRA_BAZEL_ARGS="--host_javabase=@local_jdk//:jdk" bash ./compile.sh

```



编译遇到的除架构方面的问题 

> ~~在环境变量设置的JAVA_OPTS 在编译的时候不生效,  scripts/bootstrap/compile.sh中会将这个变量unset掉, 所以直接改了这个文件, 要不然会OOM~~  这个可能是 Debian 源 openjdk的问题 . 

third_party/grpc/src/core/support/log_linux.c 文件 中  `gettid` 函数 和头文件中有重名, 需要重命名下





~编译过程中报错:~~ 这个报错是在编译 0.5.0 版本 的时候出现的, 0.10.0 版本以后没有这个问题

```
 Can't find bundle for base name com.google.errorprone.errors, locale en_US
 
 通过这个 commit 修复 
 https://github.com/bazelbuild/bazel/commit/860af5be10b6bad68144d9d2d34173e86b40268c#diff-04fd8c393b4f221ef79990ab0063ede9
 
```

ijar/zipper  段错误

在执行这个命令 压缩目录没问题, 压缩单个文件会段错误, 网上找了下 应该是代码BUG问题, 直接吧最新版 bazel 

`third_party/ijar` 目录复制过来 
