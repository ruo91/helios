# Introduction

What I wanted was an in-company CA to sign our keys for Helios clients
and masters.  Finding all the necessary bits to do this was a royal
pain as it's documented sparsely and inconsistently.  So in an effort
to reduce this for other people, I've written this.

# Generate CA cert

To do SSL with an in-company CA, you need to make the CA certificate that you
will sign everything with.  First, you need to make a key.  Obviously,
change the subject below as appropriate.

```
openssl req -new -newkey rsa:1024 -nodes -out ca.csr -keyout ca.key \
    -multivalue-rdn -subj "/DC=com/DC=example/CN=helios-ca"
```

Now sign the key with itself, and you have the cert in `ca.pem`.

```
openssl x509 -trustout -signkey ca.key -days 365 -req -in ca.csr -out ca.pem
```

# Generating Server (Helios Master) Certs With Keytool

Before we do anything, let's import the CA cert into the keystore.
```
keytool -importcert -alias root -file ca.pem \
    -storepass 'xxxxxx' -noprompt
```

Second, we generate the server key.  For the argument to `dname`, if
you are going to do server identity validation in the client, this
must be the name that it connects as.  For a wildcard cert, set
`CN=*.whatever` below.

```
keytool -genkey -alias mykey -keyalg RSA -keysize 2048 \
    -dname "DC=com,DC=example,CN=helios.cluster.example.com" \
    -keystore keystore.jks -storepass 'xxxxxx' \
    -keypass 'xxxxxx'
```

Now that we have a key, we need the CSR (certificate signing request)
that will be signed with the CA key.

```
keytool -certreq -alias mykey -file helios.cluster.example.com.csr \
    -keystore keystore.jks -storepass 'xxxxxx'
```

Now that we have the CSR, let's sign it with the CA key.  You probably want
a shorter lifetime of the cert than 65000 days (178 years), so adjust to
taste.

```
openssl x509 -sha512 -req -in helios.cluster.example.com.csr \
    -CA ca.pem -CAkey ca.key \
    -CAcreateserial -out helios.cluster.example.com.crt -days 65000
```

Now that we have the certificate, import the new cert into keystore

```
keytool -importcert -file helios.cluster.example.com.crt \
    -keystore keystore.jks -storepass 'xxxxxx'
```

Server side certificate bits should all be done now.


# Generating Client (Helios CLI) Certs With Keytool

The process here is pretty similar.

First, import the CA cert.
```
keytool -import -trustcacerts -alias root -file ca.pem -noprompt \
    -keystore keystore.jks -storepass 'xxxxxx'
```

Now generate the user key, most of `dname` is irrelevant, the only part,
from Helios's perspective, that matters is `UID`.

```
keytool -genkey -alias mykey -keyalg RSA -keysize 2048 \
    -dname "DC=com,DC=example,UID=username" \
    -keystore keystore.jks -storepass 'xxxxxx' \
    -keypass 'xxxxxx'
```

Since we have the user key, let's generate a CSR for the new key
```
keytool -certreq -alias mykey -file username.csr \
    -keystore keystore.jks -storepass xxxxxx
```

Sign it with the CA key.  Again adjust days as desired.

```
openssl x509 -sha512 -req -in drewc.csr -CA ca.pem -CAkey ca.key \
    -CAcreateserial -out user.crt -days 65000
```

Then import the new cert.  This must be done after the CA cert, or keytool
will complain about the certificate chain.
```
keytool -importcert -file username.crt \
    -keystore keystore.jks -storepass 'xxxxxx'
```


For now, during testing, you'll need to do this, as I've not figured out
how to make the client follow the certificate chain for the server you connect
to, and so it goes kaboom.  In the meantime, we import the server cert into
the client's keystore.

```
keytool -importcert -file helios.cluster.example.com.crt \
    -alias helios.cluster.example.com -noprompt \
    -keystore keystore.jks -storepass 'xxxxxx'
```

# Other Random bits

## Non-Interactive Password Provision
The OpenSSL tool allows for a `-passout` and `-passout` arguments if
you don't want to have to interactively provide passwords when signing
things.  For example:

```
openssl req -x509 -sha512 -new -nodes -key $CA_NAME.key -days 65000 \
    -out $CA_NAME.pem -passin pass:passout -multivalue-rdn \
    -subj "/DC=com/DC=example/CN=helios-ca"
```


## To Generate a Key Outside Of Keytool

http://stackoverflow.com/questions/906402/importing-an-existing-x509-certificate-and-private-key-in-java-keystore-to-use-i

