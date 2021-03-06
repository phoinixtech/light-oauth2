package com.networknt.oauth.token.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hazelcast.core.IMap;
import com.networknt.config.Config;
import com.networknt.exception.ApiException;
import com.networknt.oauth.cache.CacheStartupHookProvider;
import com.networknt.oauth.cache.OAuth2Constants;
import com.networknt.oauth.cache.model.Client;
import com.networknt.oauth.cache.model.RefreshToken;
import com.networknt.oauth.cache.model.User;
import com.networknt.oauth.token.helper.HttpAuth;
import com.networknt.security.JwtConfig;
import com.networknt.security.JwtHelper;
import com.networknt.status.Status;
import com.networknt.utility.CodeVerifierUtil;
import com.networknt.utility.HashUtil;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.form.FormData;
import io.undertow.server.handlers.form.FormDataParser;
import io.undertow.server.handlers.form.FormParserFactory;
import io.undertow.util.Headers;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.lang.JoseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.*;
import java.util.regex.Matcher;

public class Oauth2TokenPostHandler implements HttpHandler {
    private static final Logger logger = LoggerFactory.getLogger(Oauth2TokenPostHandler.class);

    private static final String UNABLE_TO_PARSE_FORM_DATA = "ERR12000";
    private static final String UNSUPPORTED_GRANT_TYPE = "ERR12001";
    private static final String MISSING_AUTHORIZATION_HEADER = "ERR12002";
    private static final String INVALID_AUTHORIZATION_HEADER = "ERR12003";
    private static final String INVALID_BASIC_CREDENTIALS = "ERR12004";
    private static final String JSON_PROCESSING_EXCEPTION = "ERR12005";
    private static final String CLIENT_NOT_FOUND = "ERR12014";
    private static final String UNAUTHORIZED_CLIENT = "ERR12007";
    private static final String INVALID_AUTHORIZATION_CODE = "ERR12008";
    private static final String GENERIC_EXCEPTION = "ERR10014";
    private static final String RUNTIME_EXCEPTION = "ERR10010";
    private static final String USERNAME_REQUIRED = "ERR12022";
    private static final String PASSWORD_REQUIRED = "ERR12023";
    private static final String INCORRECT_PASSWORD = "ERR12016";
    private static final String NOT_TRUSTED_CLIENT = "ERR12024";
    private static final String MISSING_REDIRECT_URI = "ERR12025";
    private static final String MISMATCH_REDIRECT_URI = "ERR12026";
    private static final String MISMATCH_SCOPE = "ERR12027";
    private static final String MISMATCH_CLIENT_ID = "ERR12028";
    private static final String REFRESH_TOKEN_NOT_FOUND = "ERR12029";
    private static final String USER_ID_REQUIRED_FOR_CLIENT_AUTHENTICATED_USER_GRANT_TYPE = "ERR12031";
    private static final String USER_TYPE_REQUIRED_FOR_CLIENT_AUTHENTICATED_USER_GRANT_TYPE = "ERR12032";
    private static final String INVALID_CODE_VERIFIER = "ERR12037";
    private static final String CODE_VERIFIER_TOO_SHORT = "ERR12038";
    private static final String CODE_VERIFIER_TOO_LONG = "ERR12039";
    private static final String CODE_VERIFIER_MISSING = "ERR12040";
    private static final String CODE_VERIFIER_FAILED = "ERR12041";
    private static final String INVALID_CODE_CHALLENGE_METHOD = "ERR12033";

    static JwtConfig config = (JwtConfig)Config.getInstance().getJsonObjectConfig("jwt", JwtConfig.class);

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        ObjectMapper mapper = Config.getInstance().getMapper();
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        Map<String, Object> formMap = new HashMap<>();

        final FormParserFactory parserFactory = FormParserFactory.builder().build();
        final FormDataParser parser = parserFactory.createParser(exchange);
        try {
            FormData data = parser.parseBlocking();
            for (String fd : data) {
                for (FormData.FormValue val : data.get(fd)) {
                    //logger.debug("fd = " + fd + " value = " + val.getValue());
                    formMap.put(fd, val.getValue());
                }
            }
        } catch (Exception e) {
            Status status = new Status(UNABLE_TO_PARSE_FORM_DATA, e.getMessage());
            exchange.setStatusCode(status.getStatusCode());
            exchange.getResponseSender().send(status.toString());
            return;
        }
        try {
            String grantType = (String)formMap.remove("grant_type");
            if("client_credentials".equals(grantType)) {
                exchange.getResponseSender().send(mapper.writeValueAsString(handleClientCredentials(exchange, (String)formMap.get("scope"), formMap)));
            } else if("authorization_code".equals(grantType)) {
                exchange.getResponseSender().send(mapper.writeValueAsString(handleAuthorizationCode(exchange, (String)formMap.get("code"), (String)formMap.get("redirect_uri"), formMap)));
            } else if("password".equals(grantType)) {
                char[] password = null;
                if(formMap.get("password") != null) {
                    password = ((String)formMap.get("password")).toCharArray();
                }
                exchange.getResponseSender().send(mapper.writeValueAsString(handlePassword(exchange, (String)formMap.get("username"), password, (String)formMap.get("scope"), formMap)));
            } else if("refresh_token".equals(grantType)) {
                exchange.getResponseSender().send(mapper.writeValueAsString(handleRefreshToken(exchange, (String) formMap.get("refresh_token"), (String) formMap.get("scope"), formMap)));
            } else if("client_authenticated_user".equals(grantType)) {
                exchange.getResponseSender().send(mapper.writeValueAsString(handleClientAuthenticatedUser(exchange, formMap)));
            } else {
                Status status = new Status(UNSUPPORTED_GRANT_TYPE, grantType);
                exchange.setStatusCode(status.getStatusCode());
                exchange.getResponseSender().send(status.toString());
            }
        } catch (JsonProcessingException e) {
            Status status = new Status(JSON_PROCESSING_EXCEPTION, e.getMessage());
            exchange.setStatusCode(status.getStatusCode());
            exchange.getResponseSender().send(status.toString());
        } catch (ApiException e) {
            exchange.setStatusCode(e.getStatus().getStatusCode());
            exchange.getResponseSender().send(e.getStatus().toString());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handleClientCredentials(HttpServerExchange exchange, String scope, Map<String, Object> formMap) throws ApiException {
        if(logger.isDebugEnabled()) logger.debug("scope = " + scope);
        Client client = authenticateClient(exchange, formMap);
        if(client != null) {
            if(scope == null) {
                scope = client.getScope();
            } else {
                // make sure scope is in scope defined in client.
                if(!matchScope(scope, client.getScope())) {
                    throw new ApiException(new Status(MISMATCH_SCOPE, scope, client.getScope()));
                }
            }
            String jwt;
            try {
                jwt = JwtHelper.getJwt(mockCcClaims(client.getClientId(), scope, null));
            } catch (Exception e) {
                throw new ApiException(new Status(GENERIC_EXCEPTION, e.getMessage()));
            }
            Map<String, Object> resMap = new HashMap<>();
            resMap.put("access_token", jwt);
            resMap.put("token_type", "bearer");
            resMap.put("expires_in", config.getExpiredInMinutes()*60);
            return resMap;

        }
        return new HashMap<>(); // return an empty hash map. this is actually not reachable at all.
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handleAuthorizationCode(HttpServerExchange exchange, String code, String redirectUri, Map<String, Object> formMap) throws ApiException {
        if(logger.isDebugEnabled()) logger.debug("code = " + code + " redirectUri = " + redirectUri);
        Client client = authenticateClient(exchange, formMap);
        if(client != null) {
            Map<String, String> codeMap = (Map<String, String>)CacheStartupHookProvider.hz.getMap("codes").remove(code);
            String userId = codeMap.get("userId");
            String uri = codeMap.get("redirectUri");
            String scope = codeMap.get("scope");
            // PKCE
            String codeChallenge = codeMap.get(OAuth2Constants.CODE_CHALLENGE);
            String codeChallengeMethod = codeMap.get(OAuth2Constants.CODE_CHALLENGE_METHOD);
            String codeVerifier = (String)formMap.get(OAuth2Constants.CODE_VERIFIER);

            if(userId != null) {
                // if uri is not null, redirectUri must not be null and must be identical.
                if(uri != null) {
                    if(redirectUri == null) {
                        throw new ApiException(new Status(MISSING_REDIRECT_URI, uri));
                    } else {
                        if(!uri.equals(redirectUri)) {
                            throw new ApiException(new Status(MISMATCH_REDIRECT_URI, redirectUri, uri));
                        }
                    }
                }
                IMap<String, User> users = CacheStartupHookProvider.hz.getMap("users");
                User user = users.get(userId);
                if(scope == null) {
                    scope = client.getScope();
                } else {
                    // make sure scope is in scope defined in client.
                    if(!matchScope(scope, client.getScope())) {
                        throw new ApiException(new Status(MISMATCH_SCOPE, scope, client.getScope()));
                    }
                }
                // PKCE code verifier check against code challenge
                if (codeChallenge != null && codeChallengeMethod != null) {
                    // based on whether code_challenge has been stored at corresponding authorization code request previously
                    // decide whether this client(RP) supports PKCE
                    if(codeVerifier == null || codeVerifier.trim().length() == 0) {
                        throw new ApiException(new Status(CODE_VERIFIER_MISSING));
                    }
                    if(codeVerifier.length() < CodeVerifierUtil.MIN_CODE_VERIFIER_LENGTH) {
                        throw new ApiException(new Status(CODE_VERIFIER_TOO_SHORT, codeVerifier));
                    }
                    if(codeVerifier.length() > CodeVerifierUtil.MAX_CODE_VERIFIER_LENGTH) {
                        throw new ApiException(new Status(CODE_VERIFIER_TOO_LONG, codeVerifier));
                    }

                    Matcher m = CodeVerifierUtil.VALID_CODE_CHALLENGE_PATTERN.matcher(codeVerifier);
                    if(!m.matches()) {
                        throw new ApiException(new Status(INVALID_CODE_VERIFIER, codeVerifier));
                    }

                    // https://tools.ietf.org/html/rfc7636#section-4.2
                    // plain or S256
                    if (codeChallengeMethod.equals(CodeVerifierUtil.CODE_CHALLENGE_METHOD_S256)) {
                        String s = CodeVerifierUtil.deriveCodeVerifierChallenge(codeVerifier);
                        if(!codeChallenge.equals(s)) {
                            throw new ApiException(new Status(CODE_VERIFIER_FAILED));
                        }
                    } else if(codeChallengeMethod.equals(CodeVerifierUtil.CODE_CHALLENGE_METHOD_PLAIN)){
                        if(!codeChallenge.equals(codeVerifier)) {
                            throw new ApiException(new Status(CODE_VERIFIER_FAILED));
                        }
                    } else {
                        throw new ApiException(new Status(INVALID_CODE_CHALLENGE_METHOD, codeChallengeMethod));
                    }
                }

                String jwt;
                try {
                    jwt = JwtHelper.getJwt(mockAcClaims(client.getClientId(), scope, userId, user.getUserType().toString(), null));
                } catch (Exception e) {
                    throw new ApiException(new Status(GENERIC_EXCEPTION, e.getMessage()));
                }
                // generate a refresh token and associate it with userId and clientId
                String refreshToken = UUID.randomUUID().toString();
                RefreshToken token = new RefreshToken();
                token.setRefreshToken(refreshToken);
                token.setUserId(userId);
                token.setClientId(client.getClientId());
                token.setScope(scope);
                IMap<String, RefreshToken> tokens = CacheStartupHookProvider.hz.getMap("tokens");
                tokens.set(refreshToken, token);
                Map<String, Object> resMap = new HashMap<>();
                resMap.put("access_token", jwt);
                resMap.put("token_type", "bearer");
                resMap.put("expires_in", config.getExpiredInMinutes()*60);
                resMap.put("refresh_token", refreshToken);
                return resMap;
            } else {
                throw new ApiException(new Status(INVALID_AUTHORIZATION_CODE, code));
            }
        }
        return new HashMap<>(); // return an empty hash map. this is actually not reachable at all.
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handlePassword(HttpServerExchange exchange, String userId, char[] password, String scope, Map<String, Object> formMap) throws ApiException {
        if(logger.isDebugEnabled()) logger.debug("userId = " + userId + " scope = " + scope);
        Client client = authenticateClient(exchange, formMap);
        if(client != null) {
            // authenticate user with credentials
            if(userId != null) {
                if(password != null) {
                    IMap<String, User> users = CacheStartupHookProvider.hz.getMap("users");
                    User user = users.get(userId);
                    // match password
                    try {
                        if(HashUtil.validatePassword(password, user.getPassword())) {
                            Arrays.fill(password, ' ');
                            // make sure that client is trusted
                            if(client.getClientType() == Client.ClientTypeEnum.TRUSTED) {
                                if(scope == null) {
                                    scope = client.getScope(); // use the default scope defined in client if scope is not passed in
                                } else {
                                    // make sure scope is in scope defined in client.
                                    if(!matchScope(scope, client.getScope())) {
                                        throw new ApiException(new Status(MISMATCH_SCOPE, scope, client.getScope()));
                                    }
                                }
                                String jwt = JwtHelper.getJwt(mockAcClaims(client.getClientId(), scope, userId, user.getUserType().toString(), null));

                                // generate a refresh token and associate it with userId and clientId
                                String refreshToken = UUID.randomUUID().toString();
                                RefreshToken token = new RefreshToken();
                                token.setRefreshToken(refreshToken);
                                token.setUserId(userId);
                                token.setClientId(client.getClientId());
                                token.setScope(scope);
                                IMap<String, RefreshToken> tokens = CacheStartupHookProvider.hz.getMap("tokens");
                                tokens.set(refreshToken, token);

                                Map<String, Object> resMap = new HashMap<>();
                                resMap.put("access_token", jwt);
                                resMap.put("token_type", "bearer");
                                resMap.put("expires_in", config.getExpiredInMinutes()*60);
                                resMap.put("refresh_token", refreshToken);
                                return resMap;
                            } else {
                                throw new ApiException(new Status(NOT_TRUSTED_CLIENT));
                            }
                        } else {
                            throw new ApiException(new Status(INCORRECT_PASSWORD));
                        }
                    } catch (NoSuchAlgorithmException | InvalidKeySpecException | JoseException e) {
                        throw new ApiException(new Status(GENERIC_EXCEPTION, e.getMessage()));
                    }
                } else {
                    throw new ApiException(new Status(PASSWORD_REQUIRED));
                }
            } else {
                throw new ApiException(new Status(USERNAME_REQUIRED));
            }
        }
        return new HashMap<>(); // return an empty hash map. this is actually not reachable at all.
    }


    @SuppressWarnings("unchecked")
    private Map<String, Object> handleRefreshToken(HttpServerExchange exchange, String refreshToken, String scope, Map<String, Object> formMap) throws ApiException {
        if(logger.isDebugEnabled()) logger.debug("refreshToken = " + refreshToken + " scope = " + scope);
        Client client = authenticateClient(exchange, formMap);
        if(client != null) {
            // make sure that the refresh token can be found and client_id matches.
            IMap<String, RefreshToken> tokens = CacheStartupHookProvider.hz.getMap("tokens");
            RefreshToken token = tokens.remove(refreshToken);
            if(token != null) {
                String userId = token.getUserId();
                String clientId = token.getClientId();
                String oldScope = token.getScope();
                if(client.getClientId().equals(clientId)) {
                    IMap<String, User> users = CacheStartupHookProvider.hz.getMap("users");
                    User user = users.get(userId);
                    if(scope == null) {
                        scope = oldScope; // use the previous scope when access token is generated
                    } else {
                        // make sure scope is the same as oldScope or contained in oldScope.
                        if(!matchScope(scope, oldScope)) {
                            throw new ApiException(new Status(MISMATCH_SCOPE, scope, oldScope));
                        }
                    }
                    String jwt;
                    try {
                        jwt = JwtHelper.getJwt(mockAcClaims(client.getClientId(), scope, userId, user.getUserType().toString(), null));
                    } catch (Exception e) {
                        throw new ApiException(new Status(GENERIC_EXCEPTION, e.getMessage()));
                    }
                    // generate a new refresh token and associate it with userId and clientId
                    String newRefreshToken = UUID.randomUUID().toString();
                    RefreshToken newToken = new RefreshToken();
                    newToken.setRefreshToken(newRefreshToken);
                    newToken.setUserId(userId);
                    newToken.setClientId(client.getClientId());
                    newToken.setScope(scope);
                    tokens.put(refreshToken, newToken);
                    Map<String, Object> resMap = new HashMap<>();
                    resMap.put("access_token", jwt);
                    resMap.put("token_type", "bearer");
                    resMap.put("expires_in", config.getExpiredInMinutes()*60);
                    resMap.put("refresh_token", newRefreshToken);
                    return resMap;

                } else {
                    // mismatched client id
                    throw new ApiException(new Status(MISMATCH_CLIENT_ID, client.getClientId(), clientId));
                }
            } else {
                // refresh token cannot be found.
                throw new ApiException(new Status(REFRESH_TOKEN_NOT_FOUND, refreshToken));
            }
        }
        return new HashMap<>(); // return an empty hash map. this is actually not reachable at all.
    }

    /**
     * This grant type is custom grant type that assume client has already authenticated the user and only send the user info
     * to the authorization server to get the access token. The token is similar with authorization code token. All extra info
     * from the formMap will be put into the token as custom claim.
     *
     * Also, only
     *
     * @param exchange
     * @param formMap
     * @return
     * @throws ApiException
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> handleClientAuthenticatedUser(HttpServerExchange exchange, Map<String, Object> formMap) throws ApiException {
        if(logger.isDebugEnabled()) logger.debug("client authenticated user grant formMap = " + formMap);
        Client client = authenticateClient(exchange, formMap);
        if(client != null) {
            // make sure that client is trusted
            if(Client.ClientTypeEnum.TRUSTED == client.getClientType()) {
                String scope = (String)formMap.remove("scope");
                if(scope == null) {
                    scope = client.getScope(); // use the default scope defined in client if scope is not passed in
                } else {
                    // make sure scope is in scope defined in client.
                    if(!matchScope(scope, client.getScope())) {
                        throw new ApiException(new Status(MISMATCH_SCOPE, scope, client.getScope()));
                    }
                }
                // make sure that userId and userType are passed in the formMap.
                String userId = (String)formMap.remove("userId");
                if(userId == null) {
                    throw new ApiException(new Status(USER_ID_REQUIRED_FOR_CLIENT_AUTHENTICATED_USER_GRANT_TYPE));
                }

                String userType = (String)formMap.remove("userType");
                if(userType == null) {
                    throw new ApiException(new Status(USER_TYPE_REQUIRED_FOR_CLIENT_AUTHENTICATED_USER_GRANT_TYPE));

                }
                String jwt;
                try {
                    jwt = JwtHelper.getJwt(mockAcClaims(client.getClientId(), scope, userId, userType, formMap));
                } catch (Exception e) {
                    throw new ApiException(new Status(GENERIC_EXCEPTION, e.getMessage()));
                }

                // generate a refresh token and associate it with userId and clientId
                String refreshToken = UUID.randomUUID().toString();
                RefreshToken token = new RefreshToken();
                token.setRefreshToken(refreshToken);
                token.setUserId(userId);
                token.setClientId(client.getClientId());
                token.setScope(scope);
                IMap<String, RefreshToken> tokens = CacheStartupHookProvider.hz.getMap("tokens");
                tokens.set(refreshToken, token);

                Map<String, Object> resMap = new HashMap<>();
                resMap.put("access_token", jwt);
                resMap.put("token_type", "bearer");
                resMap.put("expires_in", config.getExpiredInMinutes()*60);
                resMap.put("refresh_token", refreshToken);
                return resMap;
            } else {
                // not trusted client, this is not allowed.
                throw new ApiException(new Status(NOT_TRUSTED_CLIENT));
            }
        }
        return new HashMap<>(); // return an empty hash map. this is actually not reachable at all.
    }

    private Client authenticateClient(HttpServerExchange exchange, Map<String, Object> formMap) throws ApiException {
        HttpAuth httpAuth = new HttpAuth(exchange);

        String clientId;
        String clientSecret;
        if(!httpAuth.isHeaderAvailable()) {
            clientId = (String)formMap.remove("client_id");
            clientSecret = (String)formMap.remove("client_secret");
        } else {
            clientId = httpAuth.getClientId();
            clientSecret = httpAuth.getClientSecret();
        }

        if(clientId == null || clientId.trim().isEmpty() || clientSecret == null || clientSecret.trim().isEmpty()) {
            if(!httpAuth.isHeaderAvailable()) {
                throw new ApiException(new Status(MISSING_AUTHORIZATION_HEADER));
            } else if(httpAuth.isInvalidCredentials()) {
                throw new ApiException(new Status(INVALID_BASIC_CREDENTIALS, httpAuth.getCredentials()));
            } else {
                throw new ApiException(new Status(INVALID_AUTHORIZATION_HEADER, httpAuth.getAuth()));
            }
        }

        return validateClientSecret(clientId, clientSecret);
    }

    private Client validateClientSecret(String clientId, String clientSecret) throws ApiException {
        IMap<String, Client> clients = CacheStartupHookProvider.hz.getMap("clients");
        Client client = clients.get(clientId);
        if(client == null) {
            throw new ApiException(new Status(CLIENT_NOT_FOUND, clientId));
        } else {
            try {
                if(HashUtil.validatePassword(clientSecret.toCharArray(), client.getClientSecret())) {
                    return client;
                } else {
                    throw new ApiException(new Status(UNAUTHORIZED_CLIENT));
                }
            } catch ( NoSuchAlgorithmException | InvalidKeySpecException e) {
                logger.error("Exception:", e);
                throw new ApiException(new Status(RUNTIME_EXCEPTION));
            }
        }
    }

    private JwtClaims mockCcClaims(String clientId, String scopeString, Map<String, Object> formMap) {
        JwtClaims claims = JwtHelper.getDefaultJwtClaims();
        claims.setClaim("client_id", clientId);
        List<String> scope = Arrays.asList(scopeString.split("\\s+"));
        claims.setStringListClaim("scope", scope); // multi-valued claims work too and will end up as a JSON array
        if(formMap != null) {
            for(Map.Entry<String, Object> entry : formMap.entrySet()) {
                claims.setClaim(entry.getKey(), entry.getValue());
            }
        }
        return claims;
    }

    private JwtClaims mockAcClaims(String clientId, String scopeString, String userId, String userType, Map<String, Object> formMap) {
        JwtClaims claims = JwtHelper.getDefaultJwtClaims();
        claims.setClaim("user_id", userId);
        claims.setClaim("user_type", userType);
        claims.setClaim("client_id", clientId);
        List<String> scope = Arrays.asList(scopeString.split("\\s+"));
        claims.setStringListClaim("scope", scope); // multi-valued claims work too and will end up as a JSON array
        if(formMap != null) {
            for(Map.Entry<String, Object> entry : formMap.entrySet()) {
                claims.setClaim(entry.getKey(), entry.getValue());
            }
        }
        return claims;
    }



    private static boolean matchScope(String s1, String s2) {
        boolean matched = true;
        if(s1 == null || s2 == null) {
            matched = false;
        } else {
            if(!s1.equals(s2)) {
                String[] split = s1.split("\\s+");
                for (String aSplit : split) {
                    if (!s2.contains(aSplit)) {
                        matched = false;
                        break;
                    }
                }
            }
        }
        return matched;
    }
}
