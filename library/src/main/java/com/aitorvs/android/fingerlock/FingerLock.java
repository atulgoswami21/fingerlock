package com.aitorvs.android.fingerlock;

/*
 * Copyright (C) 23/05/16 aitorvs
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

import android.annotation.TargetApi;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.fingerprint.FingerprintManager;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import android.support.annotation.IntDef;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;
import android.util.Log;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.InvalidParameterException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;

public class FingerLock {

    private static final FingerLockImpl impl;
    private static FingerprintAuthHandler mAuthenticationHandler;

    static {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            impl = new Api23FingerLockImpl();
        } else {
            // legacy stub implementation. Disable fingerprint
            impl = new BaseFingerLockImpl();
        }
    }

    interface FingerLockImpl {
        /**
         * Returns <code>true</code> when fingerprint authentication is available and supported
         *
         * @return <code>true</code> when supported
         */
        boolean isFingerprintAuthSupported();

        boolean isFingerprintRegistered();

        /**
         * Call this method to start fingerprint scanning
         */
        void start();

        /**
         * Call this method to force stopping fingerprint scanning
         */
        void stop();

        void register(@NonNull Context context, @Nullable final String keyName, @NonNull FingerLockResultCallback callback);

        /**
         * Call this method to avoid any memory leakage. Good place is <code>onPause</code>
         *
         * @param listener previously registered listener
         * @return <code>true</code> when executed successfully
         */
        boolean unregister(@NonNull FingerLockResultCallback listener);

        boolean inUseBy(FingerLockResultCallback listener);

        void recreateKey();
    }

    /**
     * Baseline implementation, pre-M
     */
    static class BaseFingerLockImpl implements FingerLockImpl {


        @Override
        public boolean isFingerprintAuthSupported() {
            return false;
        }

        @Override
        public boolean isFingerprintRegistered() {
            return false;
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public void register(@NonNull Context context, @Nullable final String keyName, @NonNull FingerLockResultCallback callback) {
            //noinspection ConstantConditions
            if (callback != null) {
                // error out to inform the user
                callback.onFingerLockError(FINGERPRINT_NOT_SUPPORTED, new Exception("Fingerprint authentication not supported in this device"));
            }
        }

        @Override
        public boolean unregister(@NonNull FingerLockResultCallback listener) {
            return true;
        }

        @Override
        public boolean inUseBy(FingerLockResultCallback listener) {
            return false;
        }

        @Override
        public void recreateKey() {

        }
    }

    /**
     * M implementation
     */
    @TargetApi(Build.VERSION_CODES.M)
    static class Api23FingerLockImpl implements FingerLockImpl {

        private static final String TAG = Api23FingerLockImpl.class.getSimpleName();
        protected Context mContext;
        // FIXME: 25/05/16 make it an autovalue
        protected String mKeyName;
        protected FingerLockResultCallback mCallback;

        // specific of the implementation for API >=23
        private FingerprintManager mFingerprintManager;

        @Override
        public boolean isFingerprintAuthSupported() {
            invalidContext();

            // check permissions
            int granted = ContextCompat.checkSelfPermission(mContext, android.Manifest.permission.USE_FINGERPRINT);
            if (granted != PackageManager.PERMISSION_GRANTED) {
                // not granted
                return false;
            }

            // return hardware support
            //noinspection MissingPermission
            return mFingerprintManager != null
                    && mFingerprintManager.isHardwareDetected()
                    && mFingerprintManager.hasEnrolledFingerprints();

        }

        @Override
        public boolean isFingerprintRegistered() {
            if (!isFingerprintAuthSupported()) {
                return false;
            }

            // no need to check for permissions, as it's done inside previous call
            //noinspection MissingPermission
            return mFingerprintManager != null && mFingerprintManager.hasEnrolledFingerprints();
        }

        @Override
        public void start() {
            if (!isFingerprintAuthSupported()) {
                mCallback.onFingerLockError(FINGERPRINT_NOT_SUPPORTED, new Exception("Fingerprint authentication not supported in this device"));
            } else if (mAuthenticationHandler != null && !mAuthenticationHandler.isReady()) {
                // auth handler already listening...do nothing
            } else {
                try {
                    mAuthenticationHandler = new FingerprintAuthHandler(null, mCallback);
                    //noinspection MissingPermission
                    mAuthenticationHandler.start(mFingerprintManager);

                    mCallback.onFingerLockScanning(!KeyUtils.initCipher(mKeyName));
                } catch (NullKeyException e) {
                    // key is not yet created. Create it and retry
                    KeyUtils.recreateKey(mKeyName);
                    try {
                        mCallback.onFingerLockScanning(!KeyUtils.initCipher(mKeyName));
                    } catch (NullKeyException e1) {
                        // something went wrong unregister and notify
                        forceUnregister();
                        mCallback.onFingerLockError(FINGERPRINT_UNRECOVERABLE_ERROR, new Exception("Key creation failed."));
                    }
                }
            }
        }

        @Override
        public void stop() {
            if (mAuthenticationHandler != null) {
                // cancel and flag it as self cancelled
                mAuthenticationHandler.stop(true);
            }
        }

        @Override
        public void register(@NonNull Context context, @Nullable final String keyName, @NonNull FingerLockResultCallback callback) {
            // double check
            //noinspection ConstantConditions
            if (context == null || callback == null) {
                throw new InvalidParameterException("Invalid or null input parameters");
            } else if (mCallback != null) {
                // already registered, force clean unregister
                forceUnregister();
            }
            Log.e(TAG, "Registering " + keyName);

            mContext = context;
            mCallback = callback;
            mKeyName = keyName;

            // do the key thing
            if (keyName != null) {
                KeyUtils.initKeyStore();
            }

            mFingerprintManager = getFingerprintManager();

            if (!isFingerprintAuthSupported()) {
                callback.onFingerLockError(FINGERPRINT_NOT_SUPPORTED, new Exception("Fingerprint authentication not supported in this device"));
            } else if (!isFingerprintRegistered()) {
                callback.onFingerLockError(FINGERPRINT_REGISTRATION_NEEDED, new Exception("No fingerprints registered in this device"));
            } else {
                // all systems Go!
                callback.onFingerLockReady();
//                if (KeyUtils.recreateKey(keyName)) {
//                    callback.onFingerLockReady();
//                } else {
//                    callback.onFingerLockError(FINGERPRINT_REGISTRATION_NEEDED, new Exception("No fingerprints registered in this device"));
//                }
            }
        }

        @Override
        public void recreateKey() {
            if (mKeyName != null) {
                KeyUtils.recreateKey(mKeyName);
            }
        }

        @Override
        public boolean inUseBy(FingerLockResultCallback listener) {
            Log.e(TAG, "inUseBy: " + (mCallback == listener ? "true" : "false"));
            return mContext != null && mCallback != null && mCallback == listener;
        }

        @Override
        public boolean unregister(@NonNull FingerLockResultCallback listener) {
            if (mCallback == listener) {
                mCallback = null;
                mKeyName = null;
                mContext = null;

                stop();
                Log.e(TAG, "unregister: OK");
                return true;
            }

            return false;
        }

        private void forceUnregister() {
            mCallback = null;
            mKeyName = null;
            mContext = null;

            stop();
            Log.e(TAG, "Force unregister: OK");
        }

        private void invalidContext() throws IllegalStateException {
            if (mContext == null) {
                throw new IllegalStateException("Callback listener not registered");
            }
        }

        @TargetApi(Build.VERSION_CODES.M)
        @Nullable
        private FingerprintManager getFingerprintManager() {

            if (mFingerprintManager == null) {
                invalidContext();

                mFingerprintManager = (FingerprintManager) mContext.getSystemService(Context.FINGERPRINT_SERVICE);
            }

            return mFingerprintManager;
        }
    }

    public static boolean isFingerprintAuthSupported() {
        return impl.isFingerprintAuthSupported();
    }

    public static boolean isFingerprintRegistered() {
        return impl.isFingerprintRegistered();
    }

    public static void register(@NonNull Context context, @Nullable String keyName, @NonNull FingerLockResultCallback callback) {
        impl.register(context, keyName, callback);
    }

    public static void unregister(@NonNull FingerLockResultCallback listener) {
        impl.unregister(listener);
    }

    public static void start() {
        impl.start();
    }

    public static void stop() {
        impl.stop();
    }

    public static boolean inUseBy(FingerLockResultCallback listener) {
        return impl.inUseBy(listener);
    }

    public static void recreateKey() {
        impl.recreateKey();
    }

    /**
     * Helper class to handle the key store
     */
    @TargetApi(Build.VERSION_CODES.M)
    static class KeyUtils {

        private static final String TAG = KeyUtils.class.getSimpleName();
        private static KeyGenerator mKeyGenerator;
        private static Cipher mCipher;
        private static KeyStore mKeyStore;

        public static void initKeyStore() {
            try {

                mKeyStore = KeyStore.getInstance("AndroidKeyStore");
                mKeyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");

            } catch (NoSuchAlgorithmException | NoSuchProviderException e) {
                throw new RuntimeException("Failed to get the keyGenerator", e);
            } catch (KeyStoreException e) {
                throw new RuntimeException("Failed to init keyStore", e);
            }

            try {
                mCipher = Cipher.getInstance(KeyProperties.KEY_ALGORITHM_AES + "/"
                        + KeyProperties.BLOCK_MODE_CBC + "/"
                        + KeyProperties.ENCRYPTION_PADDING_PKCS7);
            } catch (NoSuchAlgorithmException | NoSuchPaddingException e) {
                throw new RuntimeException("Failed to get an instance of Cipher", e);
            }
        }

        private static boolean invalidKeyStore() {
            return mKeyGenerator == null || mKeyStore == null || mCipher == null;
        }

        public static boolean initCipher(@Nullable final String keyName) throws NullKeyException {
            if (invalidKeyStore()) {
                Log.w(TAG, "initCipher: Invalid keystore");
                return false;
            }
            if (BuildConfig.DEBUG) Log.d(TAG, "initCipher with key " + keyName);

            try {
                mKeyStore.load(null);
                SecretKey secretKey = (SecretKey) mKeyStore.getKey(keyName, null);
                if (secretKey == null) {
                    // the key has not been created. Notify so that it can be created for the first
                    // time
                    throw new NullKeyException();
                }
                mCipher.init(Cipher.ENCRYPT_MODE, secretKey);

                return true;

            } catch (KeyPermanentlyInvalidatedException e) {
                return false;
            } catch (KeyStoreException | CertificateException | UnrecoverableKeyException | IOException
                    | NoSuchAlgorithmException | InvalidKeyException e) {
                throw new RuntimeException("Failed to init Cipher", e);
            }
        }

        /**
         * Recreates the key
         *
         * @param keyName key name
         * @return <code>true</code> when re-creation is done successfully
         */
        public static boolean recreateKey(@NonNull String keyName) {
            if (invalidKeyStore()) {
                Log.w(TAG, "recreateKey: Invalid keystore");
                return false;
            }

            try {
                mKeyStore.load(null);


                // Set the alias of the entry in Android KeyStore where the key will appear
                // and the constrains (purposes) in the constructor of the Builder
                mKeyGenerator.init(new KeyGenParameterSpec.Builder(keyName,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
                        // Require the user to authenticate with a fingerprint to authorize every use
                        // of the key
                        .setUserAuthenticationRequired(true)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
                        .build());

                mKeyGenerator.generateKey();

                Log.d(TAG, String.format("Key \"%s\" recreated", keyName));

                return true;

            } catch (IOException | NoSuchAlgorithmException | CertificateException | InvalidAlgorithmParameterException e) {
                Log.e(TAG, "recreateKey: ", e);
                return false;
            }
        }
    }

    /**
     * This interface is the contract for receiving the results of the fingerprint authentication
     * activity
     */
    public interface FingerLockResultCallback {

        /**
         * This callback method is called when figerprint authentication failed
         *
         * @param errorType error type
         * @param e         exception raised
         */
        void onFingerLockError(@FingerLockErrorState int errorType, Exception e);

        /**
         * This callback method is called when the fingerprint has been recognized and authenticated
         * correctly
         */
        void onFingerLockAuthenticationSucceeded();

        /**
         * This callback method is called when the library is ready
         */
        void onFingerLockReady();

        /**
         * This callback method is called when the FingerLock is ready and scanning for fingerprints.
         * The boolean param indicates when the provide key (if provided) is invalid either because
         * the user disables the lock screen or resets or adds new fingerprint(s) after the key
         * was created.
         * In such situation it is recommended to either re-register with a new unique key or use
         * password instead for increase security.
         *
         * @param invalidKey <code>true</code> when the key store key is invalid/obsolete because
         */
        void onFingerLockScanning(boolean invalidKey);
    }


    /**
     * Error state
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({FINGERPRINT_NOT_SUPPORTED, FINGERPRINT_NOT_RECOGNIZED, FINGERPRINT_PERMISSION_DENIED, FINGERPRINT_REGISTRATION_NEEDED, FINGERPRINT_ERROR_HELP, FINGERPRINT_UNRECOVERABLE_ERROR})
    public @interface FingerLockErrorState {
    }

    public static final int FINGERPRINT_NOT_SUPPORTED = 0;
    public static final int FINGERPRINT_NOT_RECOGNIZED = 1;
    public static final int FINGERPRINT_PERMISSION_DENIED = 2;
    public static final int FINGERPRINT_REGISTRATION_NEEDED = 3;
    public static final int FINGERPRINT_ERROR_HELP = 5;
    public static final int FINGERPRINT_UNRECOVERABLE_ERROR = 6;

    private static class NullKeyException extends Exception {
    }

}
