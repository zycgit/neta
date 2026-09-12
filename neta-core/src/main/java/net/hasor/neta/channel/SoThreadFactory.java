/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel;
import java.util.concurrent.ThreadFactory;
/**
 * Strategy interface for creating named {@link ThreadFactory} instances for Neta I/O and worker thread pools.
 * <p>Within each context, Neta calls {@link #newFactory} twice: once for the I/O thread pool using
 * the template {@code "Neta-IO-%s"}, and once for the worker thread pool using
 * {@code "Neta-Worker-%s"}. The {@code %s} placeholder is replaced with a monotonically
 * increasing thread index.</p>
 * <p>The default implementation, used when no explicit factory is configured, creates daemon
 * threads that inherit the context class loader. To customize priority or integrate with framework
 * naming rules, provide an implementation through {@link NetConfig#setThreadFactory}, for example:</p>
 * <pre>
 * config.setThreadFactory((loader, template) -&gt;
 *     ThreadUtils.threadFactory(loader, template, true));
 * </pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see NetConfig#setThreadFactory
 */
@FunctionalInterface
public interface SoThreadFactory {
    /**
     * Create a thread factory from the given class loader and naming template.
     * @param loader class loader inherited by new threads
     * @param nameTemplate thread name template, usually containing a {@code %s} placeholder
     * @return ThreadFactory used to create threads
     */
    ThreadFactory newFactory(ClassLoader loader, String nameTemplate);
}
