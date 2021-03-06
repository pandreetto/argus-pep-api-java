/*
 * Copyright (c) Members of the EGEE Collaboration. 2006-2010.
 * See http://www.eu-egee.org/partners/ for details on the copyright holders.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 * $Id$
 */
package org.glite.authz.common.security;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jce.provider.X509CertificateObject;
import org.bouncycastle.openssl.PEMDecryptorProvider;
import org.bouncycastle.openssl.PEMEncryptedKeyPair;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8DecryptorProviderBuilder;
import org.bouncycastle.openssl.jcajce.JcePEMDecryptorProviderBuilder;
import org.bouncycastle.operator.InputDecryptorProvider;
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo;

/**
 * PEM files reader to extract PEM encoded private key and certificates from
 * file.
 * <p>
 * <ul>
 * <li>OpenSSL 0.9 PCKS1 format compatible
 * <li>OpenSSL 1.0 PKCS8 format compatible (requires BouncyCastle >= 1.46)
 * </ul>
 * 
 * @author Valery Tschopp &lt;valery.tschopp&#64;switch.ch&gt;
 */
public class PEMFileReader {

    /** logger */
    private Log log= LogFactory.getLog(PEMFileReader.class);

    static {
        // add BouncyCastle security provider if not already done
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    /**
     * Default constructor.
     */
    public PEMFileReader() {
    }

    /**
     * Reads the <b>first</b> available PEM encoded private key (PKCS1 and PKCS8
     * format) from a filename.
     * 
     * @param filename
     *            the filename of the file to read from
     * @param password
     *            the password of the private key if encrypted, can be
     *            <code>null</code> if the key is not encrypted
     * @return the private key
     * @throws FileNotFoundException
     *             if the file doesn't exist
     * @throws IOException
     *             if an error occurs while reading the file
     */
    public PrivateKey readPrivateKey(String filename, String password)
            throws FileNotFoundException, IOException {
        File file= new File(filename);
        return readPrivateKey(file, password);
    }

    /**
     * Reads the <b>first</b> available PEM encoded private key (PKCS1 and PKCS8
     * format) from a file object.
     * 
     * @param file
     *            the file to read from
     * @param password
     *            the password of the private key if encrypted, can be
     *            <code>null</code> if the key is not encrypted
     * @return the private key
     * @throws FileNotFoundException
     *             if the file doesn't exist
     * @throws IOException
     *             if an error occurs while reading the file
     */
    public PrivateKey readPrivateKey(File file, String password)
            throws FileNotFoundException, IOException {
        log.debug("file: " + file);
        InputStream is= new FileInputStream(file);
        try {
            return readPrivateKey(is, password);
        } catch (IOException ioe) {
            String error= "Invalid file " + file + ": " + ioe.getMessage();
            log.error(error);
            throw new IOException(error, ioe);
        }
    }

    /**
     * Reads the <b>first</b> available PEM encoded private key (PKCS1 and PKCS8
     * format) from an input stream.
     * 
     * @param is
     *            the input stream
     * @param password
     *            the password of the private key if encrypted, can be
     *            <code>null</code> if the key is not encrypted
     * @return the private key
     * @throws IOException
     *             if an error occurs while parsing the input stream
     */
    protected PrivateKey readPrivateKey(InputStream is, String password)
        throws IOException {

        JcaPEMKeyConverter converter = new JcaPEMKeyConverter().setProvider("BC");

        Reader inputStreamReader = new InputStreamReader(is);
        PEMParser reader = new PEMParser(inputStreamReader);

        PrivateKey result = null;
        Object object = reader.readObject();
        while (object != null) {

            if (object instanceof KeyPair) {
                result = ((KeyPair) object).getPrivate();
                break;
            } else if (object instanceof PrivateKey) {
                result = (PrivateKey) object;
                break;
            } else if (object instanceof PEMEncryptedKeyPair) {
                PEMDecryptorProvider decProv = new JcePEMDecryptorProviderBuilder().setProvider("BC")
                    .build(password.toCharArray());
                KeyPair keyPair = converter.getKeyPair(((PEMEncryptedKeyPair) object)
                    .decryptKeyPair(decProv));
                result = keyPair.getPrivate();
                break;
            } else if (object instanceof PEMKeyPair) {
                KeyPair keyPair = converter.getKeyPair((PEMKeyPair) object);
                result = keyPair.getPrivate();
                break;
            } else if (object instanceof PKCS8EncryptedPrivateKeyInfo) {
                PKCS8EncryptedPrivateKeyInfo encPrivKeyInfo = 
                    (PKCS8EncryptedPrivateKeyInfo) object;
                try {
                    InputDecryptorProvider pkcs8Prov = new JceOpenSSLPKCS8DecryptorProviderBuilder()
                        .build(password.toCharArray());
                    result = converter.getPrivateKey(encPrivKeyInfo.decryptPrivateKeyInfo(pkcs8Prov));
                    break;
                }catch(Exception ex){
                    log.error(ex.getMessage());
                }
            } else if (object instanceof PrivateKeyInfo) {
                PrivateKeyInfo privInfo = (PrivateKeyInfo) object;
                result = converter.getPrivateKey(PrivateKeyInfo.getInstance(privInfo));
                break;
            } else {
                log.debug("Found object: " + object.getClass().getCanonicalName());
            }

            object = reader.readObject();
        }

        try {
            reader.close();
        } catch (Exception e) {
            log.error(e.getMessage());
        }

        if (object == null) {
            String error = "No KeyPair or PrivateKey object found";
            log.error(error);
            throw new IOException(error);
        } else {
            log.debug("Object type: " + object.getClass().getCanonicalName());
        }

        return result;

    }

    /**
     * Reads all PEM encoded X.509 certificates from a file
     * 
     * @param filename
     *            the filename of the file to read from
     * @return a list of all X.509 certificates
     * @throws IOException
     *             if an error occurs while reading the file
     */
    public X509Certificate[] readCertificates(String filename)
            throws FileNotFoundException, IOException {
        File file= new File(filename);
        return readCertificates(file);
    }

    /**
     * Reads all PEM encoded X.509 certificates from a file
     * 
     * @param file
     *            the file to read from
     * @return a list of all X.509 certificates
     * @throws IOException
     *             if an error occurs while reading the file
     */
    public X509Certificate[] readCertificates(File file)
            throws FileNotFoundException, IOException {
        FileReader fileReader= new FileReader(file);
        PEMParser reader= new PEMParser(fileReader);
        List<X509Certificate> certs= new ArrayList<X509Certificate>();
        Object object= null;
        do {
            try {
                // object is null at EOF
                object= reader.readObject();
                if (object instanceof X509CertificateObject) {
                    X509Certificate cert= (X509Certificate) object;
                    certs.add(cert);
                }
            } catch (IOException e) {
                // ignored, trying to read an encrypted object in file, like a
                // encrypted private key.
            }
        } while (object != null);

        try {
            reader.close();
        } catch (Exception e) {
            // ignored
        }

        return certs.toArray(new X509Certificate[] {});
    }

}
