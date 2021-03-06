/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.conscrypt;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.Principal;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import javax.crypto.BadPaddingException;
import javax.security.auth.x500.X500Principal;
import org.conscrypt.OpenSSLX509CertificateFactory.ParsingException;

public class OpenSSLX509Certificate extends X509Certificate {
    private transient final long mContext;
    private transient Integer mHashCode;

    OpenSSLX509Certificate(long ctx) {
        mContext = ctx;
    }

    public static OpenSSLX509Certificate fromX509DerInputStream(InputStream is)
            throws ParsingException {
        @SuppressWarnings("resource")
        final OpenSSLBIOInputStream bis = new OpenSSLBIOInputStream(is, true);

        try {
            final long certCtx = NativeCrypto.d2i_X509_bio(bis.getBioContext());
            if (certCtx == 0) {
                return null;
            }
            return new OpenSSLX509Certificate(certCtx);
        } catch (Exception e) {
            throw new ParsingException(e);
        } finally {
            bis.release();
        }
    }

    public static OpenSSLX509Certificate fromX509Der(byte[] encoded)
            throws CertificateEncodingException {
        try {
            return new OpenSSLX509Certificate(NativeCrypto.d2i_X509(encoded));
        } catch (ParsingException e) {
            throw new CertificateEncodingException(e);
        }
    }

    public static List<OpenSSLX509Certificate> fromPkcs7DerInputStream(InputStream is)
            throws ParsingException {
        @SuppressWarnings("resource")
        OpenSSLBIOInputStream bis = new OpenSSLBIOInputStream(is, true);

        final long[] certRefs;
        try {
            certRefs = NativeCrypto.d2i_PKCS7_bio(bis.getBioContext(), NativeCrypto.PKCS7_CERTS);
        } catch (Exception e) {
            throw new ParsingException(e);
        } finally {
            bis.release();
        }

        if (certRefs == null) {
            return Collections.emptyList();
        }

        final List<OpenSSLX509Certificate> certs = new ArrayList<OpenSSLX509Certificate>(
                certRefs.length);
        for (int i = 0; i < certRefs.length; i++) {
            if (certRefs[i] == 0) {
                continue;
            }
            certs.add(new OpenSSLX509Certificate(certRefs[i]));
        }
        return certs;
    }

    public static OpenSSLX509Certificate fromX509PemInputStream(InputStream is)
            throws ParsingException {
        @SuppressWarnings("resource")
        final OpenSSLBIOInputStream bis = new OpenSSLBIOInputStream(is, true);

        try {
            final long certCtx = NativeCrypto.PEM_read_bio_X509(bis.getBioContext());
            if (certCtx == 0L) {
                return null;
            }
            return new OpenSSLX509Certificate(certCtx);
        } catch (Exception e) {
            throw new ParsingException(e);
        } finally {
            bis.release();
        }
    }

    public static List<OpenSSLX509Certificate> fromPkcs7PemInputStream(InputStream is)
            throws ParsingException {
        @SuppressWarnings("resource")
        OpenSSLBIOInputStream bis = new OpenSSLBIOInputStream(is, true);

        final long[] certRefs;
        try {
            certRefs = NativeCrypto.PEM_read_bio_PKCS7(bis.getBioContext(),
                    NativeCrypto.PKCS7_CERTS);
        } catch (Exception e) {
            throw new ParsingException(e);
        } finally {
            bis.release();
        }

        final List<OpenSSLX509Certificate> certs = new ArrayList<OpenSSLX509Certificate>(
                certRefs.length);
        for (int i = 0; i < certRefs.length; i++) {
            if (certRefs[i] == 0) {
                continue;
            }
            certs.add(new OpenSSLX509Certificate(certRefs[i]));
        }
        return certs;
    }

    public static OpenSSLX509Certificate fromCertificate(Certificate cert)
            throws CertificateEncodingException {
        if (cert instanceof OpenSSLX509Certificate) {
            return (OpenSSLX509Certificate) cert;
        } else if (cert instanceof X509Certificate) {
            return fromX509Der(cert.getEncoded());
        } else {
            throw new CertificateEncodingException("Only X.509 certificates are supported");
        }
    }

    @Override
    public Set<String> getCriticalExtensionOIDs() {
        String[] critOids =
                NativeCrypto.get_X509_ext_oids(mContext, NativeCrypto.EXTENSION_TYPE_CRITICAL);

        /*
         * This API has a special case that if there are no extensions, we
         * should return null. So if we have no critical extensions, we'll check
         * non-critical extensions.
         */
        if ((critOids.length == 0)
                && (NativeCrypto.get_X509_ext_oids(mContext,
                        NativeCrypto.EXTENSION_TYPE_NON_CRITICAL).length == 0)) {
            return null;
        }

        return new HashSet<String>(Arrays.asList(critOids));
    }

    @Override
    public byte[] getExtensionValue(String oid) {
        return NativeCrypto.X509_get_ext_oid(mContext, oid);
    }

    @Override
    public Set<String> getNonCriticalExtensionOIDs() {
        String[] nonCritOids =
                NativeCrypto.get_X509_ext_oids(mContext, NativeCrypto.EXTENSION_TYPE_NON_CRITICAL);

        /*
         * This API has a special case that if there are no extensions, we
         * should return null. So if we have no non-critical extensions, we'll
         * check critical extensions.
         */
        if ((nonCritOids.length == 0)
                && (NativeCrypto.get_X509_ext_oids(mContext,
                        NativeCrypto.EXTENSION_TYPE_CRITICAL).length == 0)) {
            return null;
        }

        return new HashSet<String>(Arrays.asList(nonCritOids));
    }

    @Override
    public boolean hasUnsupportedCriticalExtension() {
        return (NativeCrypto.get_X509_ex_flags(mContext) & NativeConstants.EXFLAG_CRITICAL) != 0;
    }

    @Override
    public void checkValidity() throws CertificateExpiredException,
            CertificateNotYetValidException {
        checkValidity(new Date());
    }

    @Override
    public void checkValidity(Date date) throws CertificateExpiredException,
            CertificateNotYetValidException {
        if (getNotBefore().compareTo(date) > 0) {
            throw new CertificateNotYetValidException("Certificate not valid until "
                    + getNotBefore().toString() + " (compared to " + date.toString() + ")");
        }

        if (getNotAfter().compareTo(date) < 0) {
            throw new CertificateExpiredException("Certificate expired at "
                    + getNotAfter().toString() + " (compared to " + date.toString() + ")");
        }
    }

    @Override
    public int getVersion() {
        return (int) NativeCrypto.X509_get_version(mContext) + 1;
    }

    @Override
    public BigInteger getSerialNumber() {
        return new BigInteger(NativeCrypto.X509_get_serialNumber(mContext));
    }

    @Override
    public Principal getIssuerDN() {
        return getIssuerX500Principal();
    }

    @Override
    public Principal getSubjectDN() {
        return getSubjectX500Principal();
    }

    @Override
    public Date getNotBefore() {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        calendar.set(Calendar.MILLISECOND, 0);
        NativeCrypto.ASN1_TIME_to_Calendar(NativeCrypto.X509_get_notBefore(mContext), calendar);
        return calendar.getTime();
    }

    @Override
    public Date getNotAfter() {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        calendar.set(Calendar.MILLISECOND, 0);
        NativeCrypto.ASN1_TIME_to_Calendar(NativeCrypto.X509_get_notAfter(mContext), calendar);
        return calendar.getTime();
    }

    @Override
    public byte[] getTBSCertificate() throws CertificateEncodingException {
        return NativeCrypto.get_X509_cert_info_enc(mContext);
    }

    @Override
    public byte[] getSignature() {
        return NativeCrypto.get_X509_signature(mContext);
    }

    @Override
    public String getSigAlgName() {
        String oid = getSigAlgOID();
        String algName = Platform.oidToAlgorithmName(oid);
        if (algName != null) {
            return algName;
        }
        return oid;
    }

    @Override
    public String getSigAlgOID() {
        return NativeCrypto.get_X509_sig_alg_oid(mContext);
    }

    @Override
    public byte[] getSigAlgParams() {
        return NativeCrypto.get_X509_sig_alg_parameter(mContext);
    }

    @Override
    public boolean[] getIssuerUniqueID() {
        return NativeCrypto.get_X509_issuerUID(mContext);
    }

    @Override
    public boolean[] getSubjectUniqueID() {
        return NativeCrypto.get_X509_subjectUID(mContext);
    }

    @Override
    public boolean[] getKeyUsage() {
        final boolean[] kusage = NativeCrypto.get_X509_ex_kusage(mContext);
        if (kusage == null) {
            return null;
        }

        if (kusage.length >= 9) {
            return kusage;
        }

        final boolean resized[] = new boolean[9];
        System.arraycopy(kusage, 0, resized, 0, kusage.length);
        return resized;
    }

    @Override
    public int getBasicConstraints() {
        if ((NativeCrypto.get_X509_ex_flags(mContext) & NativeConstants.EXFLAG_CA) == 0) {
            return -1;
        }

        final int pathLen = NativeCrypto.get_X509_ex_pathlen(mContext);
        if (pathLen == -1) {
            return Integer.MAX_VALUE;
        }

        return pathLen;
    }

    @Override
    public byte[] getEncoded() throws CertificateEncodingException {
        return NativeCrypto.i2d_X509(mContext);
    }

    private void verifyOpenSSL(OpenSSLKey pkey) throws CertificateException,
            NoSuchAlgorithmException, InvalidKeyException, NoSuchProviderException,
            SignatureException {
        try {
            NativeCrypto.X509_verify(mContext, pkey.getNativeRef());
        } catch (RuntimeException e) {
            throw new CertificateException(e);
        } catch (BadPaddingException e) {
            throw new SignatureException();
        }
    }

    private void verifyInternal(PublicKey key, String sigProvider) throws CertificateException,
            NoSuchAlgorithmException, InvalidKeyException, NoSuchProviderException,
            SignatureException {
        final Signature sig;
        if (sigProvider == null) {
            sig = Signature.getInstance(getSigAlgName());
        } else {
            sig = Signature.getInstance(getSigAlgName(), sigProvider);
        }

        sig.initVerify(key);
        sig.update(getTBSCertificate());
        if (!sig.verify(getSignature())) {
            throw new SignatureException("signature did not verify");
        }
    }

    @Override
    public void verify(PublicKey key) throws CertificateException, NoSuchAlgorithmException,
            InvalidKeyException, NoSuchProviderException, SignatureException {
        if (key instanceof OpenSSLKeyHolder) {
            OpenSSLKey pkey = ((OpenSSLKeyHolder) key).getOpenSSLKey();
            verifyOpenSSL(pkey);
            return;
        }

        verifyInternal(key, null);
    }

    @Override
    public void verify(PublicKey key, String sigProvider) throws CertificateException,
            NoSuchAlgorithmException, InvalidKeyException, NoSuchProviderException,
            SignatureException {
        verifyInternal(key, sigProvider);
    }

    @Override
    public String toString() {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        long bioCtx = NativeCrypto.create_BIO_OutputStream(os);
        try {
            NativeCrypto.X509_print_ex(bioCtx, mContext, 0, 0);
            return os.toString();
        } finally {
            NativeCrypto.BIO_free_all(bioCtx);
        }
    }

    @Override
    public PublicKey getPublicKey() {
        /* First try to generate the key from supported OpenSSL key types. */
        try {
            OpenSSLKey pkey = new OpenSSLKey(NativeCrypto.X509_get_pubkey(mContext));
            return pkey.getPublicKey();
        } catch (NoSuchAlgorithmException | InvalidKeyException ignored) {
        }

        /* Try generating the key using other Java providers. */
        String oid = NativeCrypto.get_X509_pubkey_oid(mContext);
        byte[] encoded = NativeCrypto.i2d_X509_PUBKEY(mContext);
        try {
            KeyFactory kf = KeyFactory.getInstance(oid);
            return kf.generatePublic(new X509EncodedKeySpec(encoded));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException ignored) {
        }

        /*
         * We couldn't find anything else, so just return a nearly-unusable
         * X.509-encoded key.
         */
        return new X509PublicKey(oid, encoded);
    }

    @Override
    public X500Principal getIssuerX500Principal() {
        final byte[] issuer = NativeCrypto.X509_get_issuer_name(mContext);
        return new X500Principal(issuer);
    }

    @Override
    public X500Principal getSubjectX500Principal() {
        final byte[] subject = NativeCrypto.X509_get_subject_name(mContext);
        return new X500Principal(subject);
    }

    @Override
    public List<String> getExtendedKeyUsage() throws CertificateParsingException {
        String[] extUsage = NativeCrypto.get_X509_ex_xkusage(mContext);
        if (extUsage == null) {
            return null;
        }

        return Arrays.asList(extUsage);
    }

    private static Collection<List<?>> alternativeNameArrayToList(Object[][] altNameArray) {
        if (altNameArray == null) {
            return null;
        }

        Collection<List<?>> coll = new ArrayList<List<?>>(altNameArray.length);
        for (int i = 0; i < altNameArray.length; i++) {
            coll.add(Collections.unmodifiableList(Arrays.asList(altNameArray[i])));
        }

        return Collections.unmodifiableCollection(coll);
    }

    @Override
    public Collection<List<?>> getSubjectAlternativeNames() throws CertificateParsingException {
        return alternativeNameArrayToList(NativeCrypto.get_X509_GENERAL_NAME_stack(mContext,
                NativeCrypto.GN_STACK_SUBJECT_ALT_NAME));
    }

    @Override
    public Collection<List<?>> getIssuerAlternativeNames() throws CertificateParsingException {
        return alternativeNameArrayToList(NativeCrypto.get_X509_GENERAL_NAME_stack(mContext,
                NativeCrypto.GN_STACK_ISSUER_ALT_NAME));
    }

    @Override
    public boolean equals(Object other) {
        if (other instanceof OpenSSLX509Certificate) {
            OpenSSLX509Certificate o = (OpenSSLX509Certificate) other;

            return NativeCrypto.X509_cmp(mContext, o.mContext) == 0;
        }

        return super.equals(other);
    }

    @Override
    public int hashCode() {
        if (mHashCode != null) {
            return mHashCode;
        }
        mHashCode = super.hashCode();
        return mHashCode;
    }

    /**
     * Returns the raw pointer to the X509 context for use in JNI calls. The
     * life cycle of this native pointer is managed by the
     * {@code OpenSSLX509Certificate} instance and must not be destroyed or
     * freed by users of this API.
     */
    public long getContext() {
        return mContext;
    }

    /**
     * Delete an extension.
     *
     * A modified copy of the certificate is returned. The original object
     * is unchanged.
     * If the extension is not present, an unmodified copy is returned.
     */
    public OpenSSLX509Certificate withDeletedExtension(String oid) {
        OpenSSLX509Certificate copy = new OpenSSLX509Certificate(NativeCrypto.X509_dup(mContext));
        NativeCrypto.X509_delete_ext(copy.getContext(), oid);
        return copy;
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (mContext != 0) {
                NativeCrypto.X509_free(mContext);
            }
        } finally {
            super.finalize();
        }
    }

    /**
     * Return a possibly null array of X509Certificates given the possibly null
     * array of DER encoded bytes.
     */
    static OpenSSLX509Certificate[] createCertChain(long[] certificateRefs) {
        if (certificateRefs == null) {
            return null;
        }
        OpenSSLX509Certificate[] certificates = new OpenSSLX509Certificate[certificateRefs.length];
        for (int i = 0; i < certificateRefs.length; i++) {
            certificates[i] = new OpenSSLX509Certificate(certificateRefs[i]);
        }
        return certificates;
    }
}
