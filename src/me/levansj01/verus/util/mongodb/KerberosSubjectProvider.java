/*
 * Copyright 2008-present MongoDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.levansj01.verus.util.mongodb;

import javax.security.auth.Subject;
import javax.security.auth.kerberos.KerberosTicket;
import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;

import me.levansj01.verus.util.mongodb.annotations.ThreadSafe;
import me.levansj01.verus.util.mongodb.diagnostics.logging.Logger;
import me.levansj01.verus.util.mongodb.diagnostics.logging.Loggers;
import me.levansj01.verus.util.mongodb.lang.NonNull;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static me.levansj01.verus.util.mongodb.assertions.Assertions.notNull;

/**
 * An implementation of {@link SubjectProvider} suitable for use as the value of the {@link MongoCredential#JAVA_SUBJECT_PROVIDER_KEY}
 * mechanism property for Kerberos credentials, created via {@link MongoCredential#createGSSAPICredential(String)}.
 * <p>
 * An instance of this class will cache a Kerberos {@link Subject} until its TGT is close to expiration, at which point it will replace
 * the {@code Subject} with a new one.
 * </p>
 * <p>
 * {@code Subject} instances are created by first constructing a {@link LoginContext} with the specified name, then calling its
 * {@link LoginContext#login()} method, and finally acquiring the {@code Subject} via a call to {@link LoginContext#getSubject()}.
 * </p>
 *
 * @see LoginContext
 * @see Subject
 * @see KerberosTicket
 * @since 4.2
 */
@ThreadSafe
public class KerberosSubjectProvider implements SubjectProvider {
    private static final Logger LOGGER = Loggers.getLogger("authenticator");
    private static final String TGT_PREFIX = "krbtgt/";

    private final String loginContextName;
    private Subject subject;

    /**
     * Construct an instance with the default login context name {@code "com.sun.security.jgss.krb5.initiate"}.
     */
    public KerberosSubjectProvider() {
        this("com.sun.security.jgss.krb5.initiate");
    }

    /**
     * Construct an instance with the specified login context name
     *
     * @param loginContextName the login context name
     */
    public KerberosSubjectProvider(@NonNull final String loginContextName) {
        this.loginContextName = notNull("loginContextName", loginContextName);
    }

    /**
     * Gets a {@code Subject} instance associated with a {@link LoginContext} after its been logged in.
     *
     * @return the non-null {@code Subject} instance
     * @throws LoginException any exception resulting from a call to {@link LoginContext#login()}
     */
    @NonNull
    public synchronized Subject getSubject() throws LoginException {
        if (subject == null || needNewSubject(subject)) {
            LOGGER.info("Creating new LoginContext and logging in the principal");
            LoginContext loginContext = new LoginContext(loginContextName);
            loginContext.login();
            subject = loginContext.getSubject();
            LOGGER.info("Login successful");
        }
        return subject;
    }

    private static boolean needNewSubject(@NonNull final Subject subject) {
        for (KerberosTicket cur : subject.getPrivateCredentials(KerberosTicket.class)) {
            if (cur.getServer().getName().startsWith(TGT_PREFIX)) {
                if (System.currentTimeMillis() > cur.getEndTime().getTime() - MILLISECONDS.convert(5, MINUTES)) {
                    LOGGER.info("The TGT is close to expiring. Time to reacquire.");
                    return true;
                }
                break;
            }
        }
        return false;
    }
}
