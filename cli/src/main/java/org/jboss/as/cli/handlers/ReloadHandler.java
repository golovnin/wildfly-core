/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2015, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.cli.handlers;

import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicReference;

import org.jboss.as.cli.CommandContext;
import org.jboss.as.cli.CommandFormatException;
import org.jboss.as.cli.CommandLineException;
import org.jboss.as.cli.Util;
import org.jboss.as.cli.accesscontrol.AccessRequirement;
import org.jboss.as.cli.accesscontrol.AccessRequirementBuilder;
import org.jboss.as.cli.accesscontrol.PerNodeOperationAccess;
import org.jboss.as.cli.embedded.EmbeddedServerLaunch;
import org.jboss.as.cli.impl.ArgumentWithValue;
import org.jboss.as.cli.impl.CLIModelControllerClient;
import org.jboss.as.cli.impl.CommaSeparatedCompleter;
import org.jboss.as.cli.operation.ParsedCommandLine;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.helpers.ClientConstants;
import org.jboss.as.protocol.StreamUtils;
import org.jboss.dmr.ModelNode;

/**
 * @author Alexey Loubyansky
 *
 */
public class ReloadHandler extends BaseOperationCommand {

    private final ArgumentWithValue adminOnly;
    // standalone only arguments
    private final ArgumentWithValue useCurrentServerConfig;
    // domain only arguments
    private final ArgumentWithValue host;
    private final ArgumentWithValue restartServers;
    private final ArgumentWithValue useCurrentDomainConfig;
    private final ArgumentWithValue useCurrentHostConfig;
    private final AtomicReference<EmbeddedServerLaunch> embeddedServerRef;
    private PerNodeOperationAccess hostReloadPermission;

    public ReloadHandler(CommandContext ctx, AtomicReference<EmbeddedServerLaunch> embeddedServerRef) {
        super(ctx, "reload", true);

        this.embeddedServerRef = embeddedServerRef;

        adminOnly = new ArgumentWithValue(this, SimpleTabCompleter.BOOLEAN, "--admin-only");

        useCurrentServerConfig = new ArgumentWithValue(this, SimpleTabCompleter.BOOLEAN, "--use-current-server-config"){
            @Override
            public boolean canAppearNext(CommandContext ctx) throws CommandFormatException {
            if(ctx.isDomainMode()) {
                return false;
            }
            return super.canAppearNext(ctx);
        }};

        restartServers = new ArgumentWithValue(this, SimpleTabCompleter.BOOLEAN, "--restart-servers"){
            @Override
            public boolean canAppearNext(CommandContext ctx) throws CommandFormatException {
            if(!ctx.isDomainMode()) {
                return false;
            }
            return super.canAppearNext(ctx);
        }};

        useCurrentDomainConfig = new ArgumentWithValue(this, SimpleTabCompleter.BOOLEAN, "--use-current-domain-config"){
            @Override
            public boolean canAppearNext(CommandContext ctx) throws CommandFormatException {
            if(!ctx.isDomainMode()) {
                return false;
            }
            return super.canAppearNext(ctx);
        }};

        useCurrentHostConfig = new ArgumentWithValue(this, SimpleTabCompleter.BOOLEAN, "--use-current-host-config"){
            @Override
            public boolean canAppearNext(CommandContext ctx) throws CommandFormatException {
            if(!ctx.isDomainMode()) {
                return false;
            }
            return super.canAppearNext(ctx);
        }};

        host = new ArgumentWithValue(this, new CommaSeparatedCompleter() {
            @Override
            protected Collection<String> getAllCandidates(CommandContext ctx) {
                return hostReloadPermission.getAllowedOn(ctx);
            }} , "--host") {
            @Override
            public boolean canAppearNext(CommandContext ctx) throws CommandFormatException {
                if(!ctx.isDomainMode()) {
                    return false;
                }
                return super.canAppearNext(ctx);
            }
        };
    }

    @Override
    protected AccessRequirement setupAccessRequirement(CommandContext ctx) {
        hostReloadPermission = new PerNodeOperationAccess(ctx, Util.HOST, null, Util.RELOAD);
        return AccessRequirementBuilder.Factory.create(ctx).any()
                .operation(Util.RELOAD)
                .requirement(hostReloadPermission)
                .build();
    }

    /* (non-Javadoc)
     * @see org.jboss.as.cli.handlers.CommandHandlerWithHelp#doHandle(org.jboss.as.cli.CommandContext)
     */
    @Override
    protected void doHandle(CommandContext ctx) throws CommandLineException {
        final ModelControllerClient client = ctx.getModelControllerClient();
        if(client == null) {
            throw new CommandLineException("Connection is not available.");
        }
        if (embeddedServerRef.get() != null) {
            doHandleEmbedded(ctx, client);
            return;
        }

        if(!(client instanceof CLIModelControllerClient)) {
            throw new CommandLineException("Unsupported ModelControllerClient implementation " + client.getClass().getName());
        }
        final CLIModelControllerClient cliClient = (CLIModelControllerClient) client;

        final ModelNode op = this.buildRequestWithoutHeaders(ctx);
        try {
            final ModelNode response = cliClient.execute(op, true);
            if(!Util.isSuccess(response)) {
                throw new CommandLineException(Util.getFailureDescription(response));
            }
        } catch(IOException e) {
            // if it's not connected it's assumed the reload is in process
            if(cliClient.isConnected()) {
                StreamUtils.safeClose(cliClient);
                throw new CommandLineException("Failed to execute :reload", e);
            }
        }

        // if I try to reconnect immediately, it'll hang for 5 sec
        // which the default connection timeout for model controller client
        // waiting half a sec on my machine works perfectly
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            throw new CommandLineException("Interrupted while pausing before reconnecting.", e);
        }
        try {
            cliClient.ensureConnected(ctx.getConfig().getConnectionTimeout() + 1000);
        } catch(CommandLineException e) {
            ctx.disconnectController();
            throw e;
        }
    }

    private void doHandleEmbedded(CommandContext ctx, ModelControllerClient client) throws CommandLineException {

        final ModelNode op = this.buildRequestWithoutHeaders(ctx);
        try {
            final ModelNode response = client.execute(op);
            if(!Util.isSuccess(response)) {
                throw new CommandLineException(Util.getFailureDescription(response));
            }
        } catch(IOException e) {
            // This shouldn't be possible, as this is a local client
            StreamUtils.safeClose(client);
            throw new CommandLineException("Failed to execute :reload", e);
        }

        final long start = System.currentTimeMillis();
        final long timeoutMillis = ctx.getConfig().getConnectionTimeout() + 1000;
        final ModelNode getStateOp = new ModelNode();
        getStateOp.get(ClientConstants.OP).set(ClientConstants.READ_ATTRIBUTE_OPERATION);
        getStateOp.get(ClientConstants.NAME).set("server-state");

        while (true) {
            String serverState = null;
            try {
                final ModelNode response = client.execute(getStateOp);
                if (Util.isSuccess(response)) {
                    serverState = response.get(ClientConstants.RESULT).asString();
                    if ("running".equals(serverState)) {
                        // we're reloaded and the server is started
                        break;
                    }
                }
            } catch (IOException|IllegalStateException e) {
                // ignore and try again
                // IOException is because ModelControllerClient method sig includes it, although
                // it should not happen with an embedded server
                // IllegalStateException is because the embedded server ModelControllerClient will
                // throw that when the server-state is "stopping"
            }

            if (System.currentTimeMillis() - start > timeoutMillis) {
                if (!"starting".equals(serverState))  {
                    throw new CommandLineException("Failed to establish connection in " + (System.currentTimeMillis() - start)
                            + "ms");
                } // else we don't wait any longer for start to finish. This is roughly consistent with
                  // non-embedded behavior, where the reload command returns as soon as a connection is
                  // re-established, not waiting for the server to completely start. In the embedded case
                  // if admin-only is not used, returning from reload may take a bit longer than it does
                  // non-embedded, because we will wait ctx.getConfig().getConnectionTimeout() + 1000
                  // (or 6000 ms by default) for *start* to complete, while non-embedded will only wait
                  // to get a connection, which may happen faster. But in the standard admin-only usage
                  // expected with embedded, a start should happen so fast that the behavior difference
                  // is not noticeable.
                  // Waiting for full start is preferable with embedded because if server logging is
                  // displayed in the console, log messages during boot can interfere with the CLI prompt
            }

            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                throw new CommandLineException("Interrupted while pausing before reconnecting.", e);
            }
        }
    }

    @Override
    protected ModelNode buildRequestWithoutHeaders(CommandContext ctx) throws CommandFormatException {
        final ParsedCommandLine args = ctx.getParsedCommandLine();

        final ModelNode op = new ModelNode();
        if(ctx.isDomainMode()) {
            if(useCurrentServerConfig.isPresent(args)) {
                throw new CommandFormatException(useCurrentServerConfig.getFullName() + " is not allowed in the domain mode.");
            }

            final String hostName = host.getValue(args);
            if(hostName == null) {
                throw new CommandFormatException("Missing required argument " + host.getFullName());
            }
            op.get(Util.ADDRESS).add(Util.HOST, hostName);

            setBooleanArgument(args, op, restartServers, "restart-servers");
            setBooleanArgument(args, op, this.useCurrentDomainConfig, "use-current-domain-config");
            setBooleanArgument(args, op, this.useCurrentHostConfig, "use-current-host-config");
        } else {
            if(host.isPresent(args)) {
                throw new CommandFormatException(host.getFullName() + " is not allowed in the standalone mode.");
            }
            if(useCurrentDomainConfig.isPresent(args)) {
                throw new CommandFormatException(useCurrentDomainConfig.getFullName() + " is not allowed in the standalone mode.");
            }
            if(useCurrentHostConfig.isPresent(args)) {
                throw new CommandFormatException(useCurrentHostConfig.getFullName() + " is not allowed in the standalone mode.");
            }
            if(restartServers.isPresent(args)) {
                throw new CommandFormatException(restartServers.getFullName() + " is not allowed in the standalone mode.");
            }

            op.get(Util.ADDRESS).setEmptyList();
            setBooleanArgument(args, op, this.useCurrentServerConfig, "use-current-server-config");
        }
        op.get(Util.OPERATION).set(Util.RELOAD);

        setBooleanArgument(args, op, adminOnly, "admin-only");
        return op;
    }

    protected void setBooleanArgument(final ParsedCommandLine args, final ModelNode op, ArgumentWithValue arg, String paramName)
            throws CommandFormatException {
        if(!arg.isPresent(args)) {
            return;
        }
        final String value = arg.getValue(args);
        if(value == null) {
            throw new CommandFormatException(arg.getFullName() + " is missing value.");
        }
        if(value.equalsIgnoreCase(Util.TRUE)) {
            op.get(paramName).set(true);
        } else if(value.equalsIgnoreCase(Util.FALSE)) {
            op.get(paramName).set(false);
        } else {
            throw new CommandFormatException("Invalid value for " + arg.getFullName() + ": '" + value + "'");
        }
    }
}
