package org.cobbzilla.wizard.main;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.cobbzilla.wizard.client.ApiClientBase;
import org.cobbzilla.wizard.util.RestResponse;

import static lombok.AccessLevel.PROTECTED;
import static org.cobbzilla.util.io.StreamUtil.readLineFromStdin;
import static org.cobbzilla.util.json.JsonUtil.toJson;

@Slf4j
public abstract class MainApiBase<OPT extends MainApiOptionsBase> extends MainBase<OPT> {

    private static final String TOKEN_PREFIX = "token:";

    @Getter(value=PROTECTED, lazy=true) private final ApiClientBase apiClient = initApiClient();
    private ApiClientBase initApiClient() {
        return new ApiClientBase(getOptions().getApiBase()) {
            @Override protected String getTokenHeader() { return getApiHeaderTokenName(); }
        };
    }

    @Override protected void preRun() { if (getOptions().requireAccount()) login(); }

    /** @return the Java object to POST as JSON for the login */
    protected abstract Object buildLoginRequest(OPT options);

    /** @return the name of the HTTP header that will hold the session id on future requests */
    protected abstract String getApiHeaderTokenName();

    /** @return the URI to POST the login request to */
    protected abstract String getLoginUri();

    protected abstract String getSessionId(RestResponse response) throws Exception;

    protected abstract void setSecondFactor(Object loginRequest, String token);

    protected void login () {
        final OPT options = getOptions();
        final String account = getOptions().getAccount();
        if (account.startsWith(TOKEN_PREFIX)) {
            final String token = account.substring(TOKEN_PREFIX.length());
            log.info("not logging in, using token provided on command line instead");
            getApiClient().pushToken(token);

        } else {
            log.info("logging in " + account + " ...");
            try {
                final Object loginRequest = buildLoginRequest(options);
                final ApiClientBase api = getApiClient();
                RestResponse response = api.post(getLoginUri(), toJson(loginRequest));
                if (response.json.contains("\"2-factor\"")) {
                    final String token = getOptions().hasTwoFactor()
                            ? getOptions().getTwoFactor()
                            : readLineFromStdin("Please enter token for 2-factor authentication: ");
                    setSecondFactor(loginRequest, token);
                    response = getApiClient().post(getLoginUri(), toJson(loginRequest));
                }
                api.pushToken(getSessionId(response));

            } catch (Exception e) {
                die("Error logging in: " + e, e);
            }
        }
    }

}
