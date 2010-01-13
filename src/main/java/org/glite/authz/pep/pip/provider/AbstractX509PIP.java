/*
 * Copyright 2010 Members of the EGEE Collaboration.
 * See http://www.eu-egee.org/partners for details on the copyright holders. 
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.glite.authz.pep.pip.provider;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.Vector;

import javax.security.auth.x500.X500Principal;

import org.glite.authz.common.config.ConfigurationException;
import org.glite.authz.common.model.Attribute;
import org.glite.authz.common.model.Request;
import org.glite.authz.common.model.Subject;
import org.glite.authz.common.util.Strings;
import org.glite.authz.pep.pip.PIPProcessingException;
import org.glite.security.util.CertUtil;
import org.glite.security.util.FileCertReader;
import org.glite.voms.PKIStore;
import org.glite.voms.PKIUtils;
import org.glite.voms.PKIVerifier;
import org.glite.voms.VOMSAttribute;
import org.glite.voms.VOMSValidator;
import org.glite.voms.ac.ACValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 */
public abstract class AbstractX509PIP extends AbstractPolicyInformationPoint {

    /** Class logger. */
    private Logger log = LoggerFactory.getLogger(SCASLegacyPIP.class);

    /** Reads a set of certificates in to a chain of {@link X509Certificate} objects. */
    private FileCertReader certReader;

    /** Whether the given cert chain must contain a proxy certificate in order to be valid. */
    private boolean requireProxyCertificate;

    /** Whether to perform PKIX validation on the incoming certificate. */
    private boolean performPKIXValidation;

    /** Whether VOMS AC support is currently enabled. */
    private boolean vomsSupportEnabled;

    /** Verifier used to validate an X.509 certificate chain which may, or may not, include AC certs. */
    private PKIVerifier certVerifier;

    /**
     * The constructor for this PIP. This constructor enables support for the VOMS attribute certificates.
     * 
     * @param pipID ID of this PIP
     * @param requireProxy whether a subject's certificate chain must require a proxy in order to be valid
     * @param eeTrustMaterial trust material used to validate the subject's end entity certificate
     * @param acTrustMaterial trust material used to validate the subject's attribute certificate certificate, may be
     *            null of AC support is not desired
     * 
     * @throws ConfigurationException thrown if the configuration of the PIP fails
     */
    public AbstractX509PIP(String pipID, boolean requireProxy, PKIStore eeTrustMaterial, PKIStore acTrustMaterial)
            throws ConfigurationException {
        super(pipID);

        requireProxyCertificate = requireProxy;

        if (eeTrustMaterial == null) {
            throw new ConfigurationException("Policy information point trust material may not be null");
        }

        if (acTrustMaterial == null) {
            vomsSupportEnabled = false;
        } else {
            vomsSupportEnabled = true;
        }

        try {
            certReader = new FileCertReader();
            certVerifier = new PKIVerifier(acTrustMaterial, eeTrustMaterial);
        } catch (Exception e) {
            throw new ConfigurationException("Unable to create X509 trust manager: " + e.getMessage());
        }
    }

    /**
     * Gets whether VOMS support is enabled.
     * 
     * @return whether VOMS support is enabled
     */
    public boolean isVOMSSupportEnabled() {
        return vomsSupportEnabled;
    }

    /**
     * Gets whether the PKIX validation is performed against the processed cert chain.
     * 
     * @return whether the PKIX validation is performed against the processed cert chain
     */
    public boolean performsPKIXValidation() {
        return performPKIXValidation;
    }

    /**
     * Sets whether the PKIX validation is performed against the processed cert chain.
     * 
     * @param perform whether the PKIX validation is performed against the processed cert chain
     */
    public void performPKIXValidation(boolean perform) {
        performPKIXValidation = perform;
    }

    /**
     * Gets the PKI certificate verifier.
     * 
     * @return PKI certificate verifier
     */
    public PKIVerifier getCertVerifier() {
        return certVerifier;
    }

    /** {@inheritDoc} */
    public boolean populateRequest(Request request) throws PIPProcessingException {
        if (!appliesToRequest(request)) {
            return false;
        }

        X509Certificate[] certChain;
        X509Certificate endEntityCert;
        Collection<Attribute> attributes;
        for (Subject subject : request.getSubjects()) {
            certChain = getCertificateChain(subject);
            if (certChain == null) {
                continue;
            }

            endEntityCert = certChain[CertUtil.findClientCert(certChain)];
            String endEntitySubjectDN = endEntityCert.getSubjectX500Principal().getName(X500Principal.RFC2253);
            if (performPKIXValidation && !certVerifier.verify(certChain)) {
                String errorMsg = "Certificate with subject DN " + endEntitySubjectDN + " failed PKIX validation";
                log.error(errorMsg);
                throw new PIPProcessingException(errorMsg);
            }

            log.debug("Extracting subject attributes from certificate with subject DN {}", endEntitySubjectDN);
            attributes = processCertChain(endEntityCert, certChain);
            if (attributes != null) {
                log.debug("Extracted subject attributes {} from certificate with subject DN {}", attributes,
                        endEntitySubjectDN);
                subject.getAttributes().addAll(attributes);
                return true;
            }
        }

        String errMsg = "Subject did not contain the required subject certificate";
        log.error(errMsg);
        throw new PIPProcessingException(errMsg);
    }

    /**
     * Checks whether this PIP applies to this request.
     * 
     * @param request the incoming request to be checked
     * 
     * @return true if this PIP applies to the request, false if not
     */
    protected abstract boolean appliesToRequest(Request request);

    /**
     * Gets the certificate chain for the subject's {@value #X509_CERT_CHAIN_ID} attribute.
     * 
     * @param subject subject from which to extract the certificate chain
     * 
     * @return the extracted certificate chain or null if the subject did not contain a chain of X.509 version 3
     *         certificates
     * 
     * @throws PIPProcessingException thrown if the subject contained more than one certificate chain or if the chain
     *             was not properly PEM encoded
     */
    private X509Certificate[] getCertificateChain(Subject subject) throws PIPProcessingException {
        String pemCertChain = null;

        for (Attribute attribute : subject.getAttributes()) {
            if (Strings.safeEquals(attribute.getId(), getCertificateAttributeId())) {
                if (pemCertChain != null || attribute.getValues().size() < 1) {
                    String errorMsg = "Subject contains more than one X509 certificate chain.";
                    log.error(errorMsg);
                    throw new PIPProcessingException(errorMsg);
                }

                if (attribute.getValues().size() == 1) {
                    pemCertChain = Strings.safeTrimOrNullString((String) attribute.getValues().iterator().next());
                }
            }
        }

        if (pemCertChain == null) {
            return null;
        }

        BufferedInputStream bis = new BufferedInputStream(new ByteArrayInputStream(pemCertChain.getBytes()));
        Vector<X509Certificate> chainVector;
        try {
            chainVector = certReader.readCertChain(bis);
        } catch (IOException e) {
            log.error("Unable to parse subject cert chain", e);
            throw new PIPProcessingException("Unable to parse subject cert chain", e);
        } finally {
            try {
                bis.close();
            } catch (IOException e) {
                log.error("Unable to close cert chain inputstream", e);
            }
        }

        X509Certificate[] certChain = chainVector.toArray(new X509Certificate[] {});
        boolean proxyPresent = false;
        for (X509Certificate cert : certChain) {
            if (cert.getVersion() < 3) {
                log.warn("Subject certificate {} is not a version 3, or greater, certificate, certificate chain ignored",
                                cert.getSubjectX500Principal().getName(X500Principal.RFC2253));
                return null;
            }
            if (requireProxyCertificate && PKIUtils.isProxy(cert)) {
                proxyPresent = true;
            }
        }

        if (requireProxyCertificate && !proxyPresent) {
            return null;
        }

        return certChain;
    }

    /**
     * Gets the ID of the Subject attribute which is expected to carry the user's certificate.
     * 
     * @return ID of the Subject attribute which is expected to carry the user's certifica
     */
    protected abstract String getCertificateAttributeId();

    /**
     * Processes one certificate chain and adds the information to the subjects in the request.
     * 
     * @param endEntityCertificate end entity certificate for the subject currently being processed
     * @param certChain the certificate chain containing the end entity certificate from which information will be
     *            extracted
     * 
     * @return the attribute extracted from the certificate chain
     * 
     * @throws PIPProcessingException thrown if there is a problem reading the information from the certificate chain
     */
    protected abstract Collection<Attribute> processCertChain(X509Certificate endEntityCertificate,
            X509Certificate[] certChain) throws PIPProcessingException;

    /**
     * Validates any VOMS attribute certificates within the cert chain and extract the attributes from within.
     * 
     * @param certChain cert chain which may contain VOMS attribute certificates
     * 
     * @return the attributes extracted from the VOMS certificate or null if there were no valid attribute certificates
     * 
     * @throws PIPProcessingException thrown if there is more than one valid attribute certificate within the
     *             certificate chain
     */
    @SuppressWarnings("unchecked")
    protected VOMSAttribute extractAttributeCertificate(X509Certificate[] certChain) throws PIPProcessingException {
        VOMSValidator vomsValidator = null;
        vomsValidator = new VOMSValidator(certChain, new ACValidator(getCertVerifier()));
        vomsValidator.validate();
        List<VOMSAttribute> attributeCertificates = vomsValidator.getVOMSAttributes();

        if (attributeCertificates == null || attributeCertificates.isEmpty()) {
            return null;
        }

        if (attributeCertificates.size() > 1) {
            String errorMsg = "End entity certificate for subject"
                    + certChain[0].getSubjectX500Principal().getName(X500Principal.RFC2253)
                    + " contains more than one attribute certificate";
            log.error(errorMsg);
            throw new PIPProcessingException(errorMsg);
        }

        return attributeCertificates.get(0);
    }
}