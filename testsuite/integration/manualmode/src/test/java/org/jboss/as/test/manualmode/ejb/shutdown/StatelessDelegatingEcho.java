/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012, Red Hat, Inc., and individual contributors
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

package org.jboss.as.test.manualmode.ejb.shutdown;

import javax.annotation.PreDestroy;
import javax.ejb.Asynchronous;
import javax.ejb.DependsOn;
import javax.ejb.EJB;
import javax.ejb.Remote;
import javax.ejb.Stateless;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * @author Stuart Douglas
 */
@Stateless
@Remote(RemoteEcho.class)
@DependsOn("RealEcho")
public class StatelessDelegatingEcho implements RemoteEcho {

    //we make two echo invocations as part of the test.
    //after this the EJB is allowed to shut down
    private static CountDownLatch shutdownLatch = new CountDownLatch(1);


    @EJB(lookup = "java:module/RealEcho")
    private RemoteEcho echoOnServerTwo;

    @Override
    public String echo(String msg) {
        return this.echoOnServerTwo.echo(msg);
    }

    /**
     * This needs to be asyncrounous, as soon as the latch is triggered
     * the container shuts down and the Endpoint is closed.
     */
    @Override
    @Asynchronous
    public void testDone() {
        shutdownLatch.countDown();
    }

    /**
     * Wait for the remote call before shutting down
     */
    @PreDestroy
    private void shutdown() throws InterruptedException {
        shutdownLatch.await(20, TimeUnit.SECONDS);
    }


}
