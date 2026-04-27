package com.monkey.proudAuth.velocity.instrumentation;

import com.monkey.proudAuth.common.logging.ProudAuthConsoleLogger;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.utility.JavaModule;

import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicBoolean;

public final class VelocityOnlineModeDisconnectInstrumentation {

    private static final String LOGIN_INBOUND_CONNECTION =
            "com.velocitypowered.proxy.connection.client.LoginInboundConnection";
    private static final String INITIAL_INBOUND_CONNECTION =
            "com.velocitypowered.proxy.connection.client.InitialInboundConnection";
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    private final ProudAuthConsoleLogger logger;
    private ResettableClassFileTransformer transformer;
    private Instrumentation instrumentation;

    public VelocityOnlineModeDisconnectInstrumentation(ProudAuthConsoleLogger logger) {
        this.logger = logger;
    }

    public void install() {
        if (!INSTALLED.compareAndSet(false, true)) {
            logger.info("Velocity online-mode disconnect instrumentation gia installata.");
            return;
        }

        try {
            instrumentation = ByteBuddyAgent.install();
            transformer = new AgentBuilder.Default()
                    .ignore(
                            ElementMatchers.nameStartsWith("net.bytebuddy.")
                                    .or(ElementMatchers.nameStartsWith("java."))
                                    .or(ElementMatchers.nameStartsWith("javax."))
                                    .or(ElementMatchers.nameStartsWith("jdk."))
                                    .or(ElementMatchers.nameStartsWith("sun."))
                    )
                    .disableClassFormatChanges()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
                    .type(
                            ElementMatchers.named(LOGIN_INBOUND_CONNECTION)
                                    .or(ElementMatchers.named(INITIAL_INBOUND_CONNECTION))
                    )
                    .transform(new AgentBuilder.Transformer() {
                        @Override
                        public DynamicType.Builder<?> transform(
                                DynamicType.Builder<?> builder,
                                TypeDescription typeDescription,
                                ClassLoader classLoader,
                                JavaModule module,
                                ProtectionDomain protectionDomain
                        ) {
                            return builder.visit(
                                    Advice.to(VelocityOnlineModeDisconnectAdvice.class)
                                            .on(
                                                    ElementMatchers.named("disconnect")
                                                            .and(ElementMatchers.takesArguments(1))
                                                            .and(ElementMatchers.takesArgument(
                                                                    0,
                                                                    ElementMatchers.named("net.kyori.adventure.text.Component")
                                                            ))
                                            )
                            );
                        }
                    })
                    .installOn(instrumentation);

            logger.success("Velocity online-mode disconnect instrumentation installata.");
        } catch (Throwable throwable) {
            INSTALLED.set(false);
            logger.warn("Impossibile installare l'instrumentation Velocity per il messaggio premium: "
                    + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
        }
    }

    public void updateMessage(String miniMessage) {
        VelocityOnlineModeDisconnectAdvice.updateMessage(miniMessage);
    }

    public void shutdown() {
        if (transformer == null || instrumentation == null) {
            return;
        }
        try {
            transformer.reset(instrumentation, AgentBuilder.RedefinitionStrategy.RETRANSFORMATION);
            logger.info("Velocity online-mode disconnect instrumentation rimossa.");
        } catch (Throwable throwable) {
            logger.warn("Impossibile rimuovere l'instrumentation Velocity: "
                    + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
        } finally {
            transformer = null;
            instrumentation = null;
            INSTALLED.set(false);
        }
    }
}
