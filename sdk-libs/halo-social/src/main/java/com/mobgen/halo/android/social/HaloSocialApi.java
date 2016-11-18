package com.mobgen.halo.android.social;

import android.accounts.Account;
import android.support.annotation.CheckResult;
import android.support.annotation.IntDef;
import android.support.annotation.Keep;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.util.SparseArray;

import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.mobgen.halo.android.framework.common.annotations.Api;
import com.mobgen.halo.android.framework.common.exceptions.HaloConfigurationException;
import com.mobgen.halo.android.framework.common.helpers.builder.IBuilder;
import com.mobgen.halo.android.framework.common.helpers.logger.Halog;
import com.mobgen.halo.android.framework.common.utils.AssertionUtils;
import com.mobgen.halo.android.framework.toolbox.data.CallbackV2;
import com.mobgen.halo.android.sdk.api.Halo;
import com.mobgen.halo.android.sdk.api.HaloPluginApi;
import com.mobgen.halo.android.sdk.core.management.authentication.AuthenticationRecover;
import com.mobgen.halo.android.sdk.core.management.authentication.HaloSocial;
import com.mobgen.halo.android.sdk.core.threading.HaloInteractorExecutor;
import com.mobgen.halo.android.social.authenticator.AccountManagerHelper;
import com.mobgen.halo.android.social.models.HaloAuthProfile;
import com.mobgen.halo.android.social.models.HaloUserProfile;
import com.mobgen.halo.android.social.models.IdentifiedUser;
import com.mobgen.halo.android.social.providers.SocialNotAvailableException;
import com.mobgen.halo.android.social.providers.SocialProvider;
import com.mobgen.halo.android.social.providers.facebook.FacebookSocialProvider;
import com.mobgen.halo.android.social.providers.google.GoogleSocialProvider;
import com.mobgen.halo.android.social.providers.halo.HaloSocialProvider;
import com.mobgen.halo.android.social.register.RegisterInteractor;
import com.mobgen.halo.android.social.register.RegisterRemoteDatasource;
import com.mobgen.halo.android.social.register.RegisterRepository;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Plugin for the social network handling and login with different profiles of app +.
 * It allows to use the halo login, facebook, google plus or another declared provider.
 */
@Keep
public class HaloSocialApi extends HaloPluginApi implements HaloSocial<IdentifiedUser, HaloAuthProfile, HaloUserProfile, SocialNotAvailableException> {

    /**
     * Determines the
     */
    @IntDef({SOCIAL_HALO, SOCIAL_GOOGLE_PLUS, SOCIAL_FACEBOOK})
    @Retention(RetentionPolicy.SOURCE)
    public @interface SocialType {
    }

    /**
     * Identifier for social login with halo.
     */
    @Api(2.1)
    public static final int SOCIAL_HALO = 0;
    /**
     * Identifier for social login with google plus.
     */
    @Api(2.1)
    public static final int SOCIAL_GOOGLE_PLUS = 1;
    /**
     * Identifier for social login with facebook.
     */
    @Api(2.1)
    public static final int SOCIAL_FACEBOOK = 2;

    /**
     * Determines the
     */
    @IntDef({RECOVERY_NEVER, RECOVERY_ALWAYS})
    @Retention(RetentionPolicy.SOURCE)
    public @interface RecoveryPolicy {
    }

    /**
     * Policy to recover credentials from account manager
     */
    @Api(2.1)
    public static final int RECOVERY_NEVER = 0;
    /**
     * Policy to recover credentials from account manager
     */
    @Api(2.1)
    public static final int RECOVERY_ALWAYS = 1;

    /**
     * Possible errors that can happen when using a provider for loging in.
     */
    public class Error {
        /**
         * The user has no internet.
         */
        public static final int CODE_NO_INTERNET = 1;
        /**
         * There was an error related to the social network. This usually means
         * you have to perform some actions to get that social network to work, like
         * adding some credentials or registering an api key.
         */
        public static final int CODE_PROVIDER_ERROR = 2;
    }

    /**
     * The Account manager helper
     */
    private AccountManagerHelper mAccountManagerHelper;
    /**
     * The recovery policy. By default recovery policy equals never store.
     */
    private int mRecoveryPolicy = HaloSocialApi.RECOVERY_NEVER;

    /**
     * The map of providers.
     */
    private SparseArray<SocialProvider> mProviders;
    /**
     * The account type on account manager.
     */
    private String mAccountType;

    /**
     * Constructor that accepts halo.
     *
     * @param halo The halo instance.
     */
    private HaloSocialApi(@NonNull Halo halo) {
        super(halo);
        mProviders = new SparseArray<>(3);
        AuthenticationRecover haloSocialRecover = new AuthenticationRecover() {
            @Override
            public void recoverAccount() {
                recoverSocialProviderAccount();
            }
        };
        halo.getCore().haloSocialRecover(haloSocialRecover);
        halo.getCore().haloSocial(this);
    }

    /**
     * Creates the social api for authentications.
     *
     * @param halo The halo instance.
     * @return The social api instance.
     */
    @Keep
    public static Builder with(@NonNull Halo halo) {
        return new Builder(halo);
    }

    /**
     * Tries to login with halo based on the id of this social network and authorization profile.
     *
     * @param socialNetwork   The social network to login with.
     * @param haloAuthProfile The auth profile to login on Halo.
     * @param callback        The callback.
     * @throws SocialNotAvailableException SocialNotAvailableException.
     */
    @Keep
    @Api(2.1)
    @Override
    public void loginWithHalo(@SocialType int socialNetwork, @Nullable HaloAuthProfile haloAuthProfile, @NonNull CallbackV2<IdentifiedUser> callback) throws SocialNotAvailableException {
        login(socialNetwork, haloAuthProfile, callback);
    }

    /**
     * Tries to login with a social network based on the id of this social network
     *
     * @param socialNetwork The social network to login with.
     * @param callback      The callback.
     * @throws SocialNotAvailableException SocialNotAvailableException.
     */
    @Keep
    @Api(2.1)
    @Override
    public void loginWithSocial(@SocialType int socialNetwork, @NonNull CallbackV2<IdentifiedUser> callback) throws SocialNotAvailableException {
        login(socialNetwork, null, callback);
    }

    /**
     * Tries to sign in with halo
     *
     * @param haloAuthProfile The auth profile to register.
     * @param haloUserProfile The user profile.
     */
    @Api(2.1)
    @Keep
    @Override
    @NonNull
    @CheckResult(suggest = "You may want to call execute() to run the task")
    public HaloInteractorExecutor<HaloUserProfile> register(@NonNull HaloAuthProfile haloAuthProfile, @NonNull HaloUserProfile haloUserProfile) {
        AssertionUtils.notNull(haloAuthProfile, "haloAuthProfile");
        AssertionUtils.notNull(haloUserProfile, "haloUserProfile");
        haloAuthProfile.setAlias(halo().manager().getDevice().getAlias());
        return new HaloInteractorExecutor<>(halo(),
                "Sign in with halo",
                new RegisterInteractor(new RegisterRepository(new RegisterRemoteDatasource(halo().framework().network())),
                        haloAuthProfile, haloUserProfile)
        );
    }

    /**
     * Get the recovery policy.
     *
     * @return The recovery policyß
     */
    @Keep
    @Api(2.1)
    @RecoveryPolicy
    @Override
    public int recoveryPolicy() {
        return mRecoveryPolicy;
    }

    /**
     * Get the account type.
     *
     * @return The recovery policyß
     */
    @Keep
    @Api(2.1)
    @RecoveryPolicy
    @Override
    public String accountType() {
        return mAccountType;
    }

    /**
     * Checks if the social network with the given id is available.
     *
     * @param socialNetwork The social network.
     * @return True if it is available. False otherwise.
     */
    @Keep
    @Api(2.1)
    public boolean isSocialNetworkAvailable(int socialNetwork) {
        return hasProvider(socialNetwork) && libraryAvailable(socialNetwork) && linkedAppAvailable(socialNetwork);
    }

    /**
     * Releases all the reserved providers.
     */
    @Keep
    @Api(2.1)
    public void release() {
        for (int i = 0; i < mProviders.size(); i++) {
            SocialProvider provider = mProviders.get(i);
            if (provider != null) {
                provider.release();
            }
        }
        mProviders.clear();
    }

    /**
     * Tries to login with a social network based on the id of this social network and authorization profile.
     *
     * @param socialNetwork   The social network to login with.
     * @param haloAuthProfile The auth profile to login on Halo.
     * @param callback        The callback.
     * @throws SocialNotAvailableException SocialNotAvailableException.
     */
    private void login(@SocialType int socialNetwork, @Nullable HaloAuthProfile haloAuthProfile, @NonNull CallbackV2<IdentifiedUser> callback) throws SocialNotAvailableException {
        AssertionUtils.notNull(callback, "callback");
        if (!isSocialNetworkAvailable(socialNetwork)) {
            throw new SocialNotAvailableException("The social network you are trying to log with is not available. Social network id: " + socialNetwork);
        }
        if (haloAuthProfile != null) {
            mProviders.get(socialNetwork).setAuthProfile(haloAuthProfile);
        }
        mProviders.get(socialNetwork).authenticate(halo(), callback);
    }

    /**
     * Recover an account from account manager with a social network provider. Default behaviour is using Halo account.
     * We do not use the callback because its a process to recover account.
     */
    private void recoverSocialProviderAccount() {
        if (mRecoveryPolicy == HaloSocialApi.RECOVERY_ALWAYS) {
            Account account = mAccountManagerHelper.recoverAccount();
            if (account != null) {
                if (mAccountManagerHelper.getTokenProvider(account).equals(AccountManagerHelper.HALO_AUTH_PROVIDER)) {
                    HaloAuthProfile haloAuthProfile = recoverHaloAuthProfile();
                    mProviders.get(SOCIAL_HALO).setAuthProfile(haloAuthProfile);
                    mProviders.get(SOCIAL_HALO).authenticate(halo(), null);
                } else if (mAccountManagerHelper.getTokenProvider(account).equals(AccountManagerHelper.GOOGLE_AUTH_PROVIDER)) {
                    mProviders.get(SOCIAL_GOOGLE_PLUS).setSocialToken(recoverAuthToken(AccountManagerHelper.GOOGLE_AUTH_PROVIDER));
                    mProviders.get(SOCIAL_GOOGLE_PLUS).authenticate(halo(), null);
                } else if (mAccountManagerHelper.getTokenProvider(account).equals(AccountManagerHelper.FACEBOOK_AUTH_PROVIDER)) {
                    mProviders.get(SOCIAL_FACEBOOK).setSocialToken(recoverAuthToken(AccountManagerHelper.FACEBOOK_AUTH_PROVIDER));
                    mProviders.get(SOCIAL_FACEBOOK).authenticate(halo(), null);
                }
            }
        }
    }

    /**
     * Tries to recover a halo auth profile for a given account.
     *
     * @return HaloAuthProfile The HaloAuthProfile.
     */
    @Nullable
    private HaloAuthProfile recoverHaloAuthProfile() {
        return mAccountManagerHelper.getAuthProfile(mAccountManagerHelper.recoverAccount(), halo().manager().getDevice().getAlias());
    }

    /**
     * Tries to recover a halo auth token for a given account.
     *
     * @param tokenProvider The social token provider.
     * @return HaloAuthProfile The HaloAuthProfile.
     */
    @Nullable
    private String recoverAuthToken(@NonNull String tokenProvider) {
        AssertionUtils.notNull(tokenProvider, "tokenProvider");
        return mAccountManagerHelper.getAuthToken(mAccountManagerHelper.recoverAccount(), tokenProvider);
    }

    /**
     * Set the account manager helper to manager accounts with android.
     *
     * @param accountType   The account type to store.
     * @param recoverPolicy The recovery policy.
     */
    private void setAccounManager(@Nullable String accountType, @RecoveryPolicy int recoverPolicy) {
        if (recoverPolicy == RECOVERY_ALWAYS) {
            mRecoveryPolicy = recoverPolicy;
            mAccountType = accountType;
            mAccountManagerHelper = new AccountManagerHelper(context(), mAccountType);
        }
    }

    /**
     * Registers a new provider so it can be used for authentications.
     *
     * @param socialNetworkId The social network id.
     * @param provider        The provider registered.
     */
    private void registerProvider(int socialNetworkId, @Nullable SocialProvider provider) {
        if (hasProvider(socialNetworkId)) {
            Halog.w(getClass(), "You are overriding an existing social provider. Make sure you have a unique id for it.");
        }
        mProviders.put(socialNetworkId, provider);
    }

    /**
     * Checks if the application to which the network is linked in is available.
     *
     * @param socialNetwork The social network to check.
     * @return Trie if it is available. False otherwise.
     */
    private boolean linkedAppAvailable(int socialNetwork) {
        return mProviders.get(socialNetwork).linkedAppAvailable(context());
    }

    /**
     * Checks if the library is available in the platform.
     *
     * @param socialNetwork The social network to check.
     * @return True if the library is available. False otherwise.
     */
    private boolean libraryAvailable(int socialNetwork) {
        return mProviders.get(socialNetwork).isLibraryAvailable(context());
    }

    /**
     * Checks if the provider is already registered.
     *
     * @param socialNetwork The social network to login with.
     * @return True if the provider is there, false otherwise.
     */
    private boolean hasProvider(int socialNetwork) {
        return mProviders.get(socialNetwork) != null;
    }

    /**
     * The builder for the social api.
     */
    @Keep
    public static class Builder implements IBuilder<HaloSocialApi> {

        /**
         * The social api.
         */
        @NonNull
        private HaloSocialApi mSocialApi;
        /**
         * The recovery policy. By default recovery policy equals never store.
         */
        private int mRecoveryPolicy = HaloSocialApi.RECOVERY_NEVER;
        /**
         * The account type on account manager.
         */
        private String mAccountType;

        /**
         * The social api builder.
         *
         * @param halo The halo builder.
         */
        private Builder(@NonNull final Halo halo) {
            mSocialApi = new HaloSocialApi(halo);
        }

        /**
         * Set the account type to save account details on account mananger. This is optional and if its null credentials will not be stored.
         *
         * @param accountType The account type.
         * @return The account type.
         */
        @Keep
        @Api(2.1)
        @NonNull
        public Builder storeCredentials(@Nullable String accountType) {
            mAccountType = accountType;
            return this;
        }


        /**
         * Set the recovery policy.
         *
         * @param recoverPolicy The recovery policy
         * @return The current builder
         */
        @Api(2.1)
        @Keep
        @NonNull
        public Builder recoveryPolicy(@Nullable int recoverPolicy) {
            mRecoveryPolicy = recoverPolicy;
            return this;
        }

        /**
         * Adds the google provider to the social api login.
         *
         * @return The current builder.
         */
        @Keep
        @Api(2.1)
        @NonNull
        public Builder withGoogle() {
            String clientId = mSocialApi.halo().context().getString(R.string.halo_social_google_client);
            if (TextUtils.isEmpty(clientId)) {
                throw new HaloConfigurationException("You must add in the plugin the id for the social network. halo {\n" +
                        "\t...\n" +
                        "\tsocial {\n" +
                        "\t\tgoogle \"oAuth2 Web client id\"\n" +
                        "\t\t...\n" +
                        "\t}\n" +
                        "\t...\n" +
                        "}");
            }
            GoogleSignInOptions options = new GoogleSignInOptions.Builder()
                    .requestId()
                    .requestIdToken(clientId)
                    .requestEmail()
                    .requestProfile()
                    .build();
            return withProvider(SOCIAL_GOOGLE_PLUS, new GoogleSocialProvider(options));
        }

        /**
         * Adds the halo provider to the social api login.
         *
         * @return The current builder.
         */
        @Keep
        @Api(2.1)
        @NonNull
        public Builder withHalo() {
            return withProvider(SOCIAL_HALO, new HaloSocialProvider());
        }

        /**
         * Adds the facebook provider to the social api login.
         *
         * @return The current builder.
         */
        @Keep
        @Api(2.1)
        @NonNull
        public Builder withFacebook() {
            return withProvider(SOCIAL_FACEBOOK, new FacebookSocialProvider());
        }

        /**
         * Adds a custom provider.
         *
         * @param socialId The social id of the custom provider.
         * @param provider The provider instance.
         * @return The current builder.
         */
        @Keep
        @Api(2.1)
        @NonNull
        public Builder withProvider(int socialId, @Nullable SocialProvider provider) {
            mSocialApi.registerProvider(socialId, provider);
            return this;
        }

        @NonNull
        @Override
        public HaloSocialApi build() {
            mSocialApi.setAccounManager(mAccountType, mRecoveryPolicy);
            return mSocialApi;
        }
    }
}