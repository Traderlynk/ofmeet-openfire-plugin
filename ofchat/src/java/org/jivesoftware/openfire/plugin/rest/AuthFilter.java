package org.jivesoftware.openfire.plugin.rest;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response.Status;

import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.admin.AdminManager;
import org.jivesoftware.openfire.auth.AuthFactory;
import org.jivesoftware.openfire.auth.ConnectionException;
import org.jivesoftware.openfire.auth.InternalUnauthenticatedException;
import org.jivesoftware.openfire.auth.UnauthorizedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.jersey.spi.container.ContainerRequest;
import com.sun.jersey.spi.container.ContainerRequestFilter;

/**
 * The Class AuthFilter.
 */
public class AuthFilter implements ContainerRequestFilter {

    /** The log. */
    private static Logger LOG = LoggerFactory.getLogger(AuthFilter.class);

    /** The http request. */
    @Context
    private HttpServletRequest httpRequest;

    /** The plugin. */
    private RESTServicePlugin plugin = RESTServicePlugin.getInstance();

    /*
     * (non-Javadoc)
     *
     * @see
     * com.sun.jersey.spi.container.ContainerRequestFilter#filter(com.sun.jersey
     * .spi.container.ContainerRequest)
     */
    @Override
    public ContainerRequest filter(ContainerRequest containerRequest) throws WebApplicationException {
        if (!plugin.isEnabled()) {
            throw new WebApplicationException(Status.FORBIDDEN);
        }

        // Let the preflight request through the authentication
        if ("OPTIONS".equals(containerRequest.getMethod())) {
            return containerRequest;
        }

        if (!plugin.getAllowedIPs().isEmpty()) {
            // Get client's IP address
            String ipAddress = httpRequest.getHeader("x-forwarded-for");
            if (ipAddress == null) {
                ipAddress = httpRequest.getHeader("X_FORWARDED_FOR");
                if (ipAddress == null) {
                    ipAddress = httpRequest.getHeader("X-Forward-For");
                    if (ipAddress == null) {
                        ipAddress = httpRequest.getRemoteAddr();
                    }
                }
            }
            if (!plugin.getAllowedIPs().contains(ipAddress)) {
                LOG.warn("REST API rejected service to IP address: " + ipAddress);
                throw new WebApplicationException(Status.UNAUTHORIZED);
            }
        }

        // To be backwards compatible to userservice 1.*

        if ("restapi/v1/userservice".equals(containerRequest.getPath())) {
            return containerRequest;
        }

        // Get the authentification passed in HTTP headers parameters
        String auth = containerRequest.getHeaderValue("authorization");

        if (auth == null) {
            throw new WebApplicationException(Status.UNAUTHORIZED);
        }

        // if master secret used, allow everything

        if (auth.equals(plugin.getSecret())) {
            return containerRequest;
        }

        // if permission code is used, allow everything except admin
        // TODO fix solo plugin that will be broken by this change

        if (containerRequest.getPath().startsWith("restapi/v1/admin") == false)
        {
            if (auth.equals(plugin.getPermission())) {
                return containerRequest;
            }
        }

        // accept user based authentication if allowed

        if ("basic".equals(plugin.getHttpAuth()))
        {
            String[] usernameAndPassword = BasicAuth.decode(auth);

            // If username or password fail
            if (usernameAndPassword == null || usernameAndPassword.length != 2) {
                throw new WebApplicationException(Status.UNAUTHORIZED);
            }

            if (containerRequest.getPath().startsWith("restapi/v1/admin"))
            {
                boolean userAdmin = AdminManager.getInstance().isUserAdmin(usernameAndPassword[0], true);

                if (!userAdmin) {
                    LOG.warn("REST authorization fail: not admin user " + usernameAndPassword[0]);
                    throw new WebApplicationException(Status.UNAUTHORIZED);
                }
            }

            try {
                AuthFactory.authenticate(usernameAndPassword[0], usernameAndPassword[1]);
            } catch (UnauthorizedException e) {
                throw new WebApplicationException(Status.UNAUTHORIZED);
            } catch (ConnectionException e) {
                throw new WebApplicationException(Status.UNAUTHORIZED);
            } catch (InternalUnauthenticatedException e) {
                throw new WebApplicationException(Status.UNAUTHORIZED);
            }
        } else {
            if (!auth.equals(plugin.getSecret())) {
                LOG.warn("Wrong secret key authorization. Provided key: " + auth);
                throw new WebApplicationException(Status.UNAUTHORIZED);
            }
        }
        return containerRequest;
    }
}
