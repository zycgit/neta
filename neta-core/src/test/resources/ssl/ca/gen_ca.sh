#!/bin/bash

## 后续SAAS可以考虑用户从console下载自己uid关联的crt、key和pem文件放到指定目录，来使得sidecar可以访问接入console，但是下载pem需要审核，需要有接口调用次数限制、流量限制等各种限制确保console的安全
## sidecar托管出去主要是安全问题，要防止恶意攻击等
## 请在阿里云ECS上通过openssl生成证书，mac本地生成的证书在阿里云机器上有兼容性问题

## reference https://stackoverflow.com/questions/37714558/how-to-enable-server-side-ssl-for-grpc
echo Generate CA key:
openssl genrsa -passout pass:clougence2020 -des3 -out ca.key 4096

echo Generate CA certificate:
openssl req -passin pass:clougence2020 -new -x509 -days 365000 -key ca.key -out ca.crt -subj "/CN=*.hasor.net"

echo Generate server key:
openssl genrsa -passout pass:clougence2020 -des3 -out server.key 4096

echo Generate server signing request:
openssl req -passin pass:clougence2020 -new -key server.key -out server.csr -subj "/CN=*.hasor.net"

echo Self-sign server certificate:
openssl x509 -req -passin pass:clougence2020 -days 365000 -in server.csr -CA ca.crt -CAkey ca.key -set_serial 01 -out server.crt

echo Remove passphrase from server key:
openssl rsa -passin pass:clougence2020 -in server.key -out server.key

# 单向认证，client不需要生成秘钥和证书，只要一个cloudcanal提供的ca证书即可
echo Generate client key
openssl genrsa -passout pass:clougence2020 -des3 -out client.key 4096

echo Generate client signing request:
openssl req -passin pass:clougence2020 -new -key client.key -out client.csr -subj "/CN=localhost"

echo Self-sign client certificate:
openssl x509 -passin pass:clougence2020 -req -days 365000 -in client.csr -CA ca.crt -CAkey ca.key -set_serial 01 -out client.crt

echo Remove passphrase from client key:
openssl rsa -passin pass:clougence2020 -in client.key -out client.key

echo Generate client pem file
openssl pkcs8 -topk8 -nocrypt -in client.key -out client.pem
echo Generate server pem file
openssl pkcs8 -topk8 -nocrypt -in server.key -out server.pem
