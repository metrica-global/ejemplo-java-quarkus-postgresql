package com.zonacards.app;

import com.zonacards.eventx.EventCodec;
import com.zonacards.giftcards.container.GiftcardsContainer;
import com.zonacards.lotes.container.LotesContainer;
import com.zonacards.server.AppModule;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.util.List;

/**
 * Root container of the application: the list of its modules, and the few things every module
 * shares.
 *
 * <p>This is one application, built into one image, and it always contains every module below.
 * Which of them a deployment <em>serves</em> is decided by configuration ({@code ZC_MODULES}):
 * all of them in one service, or one module on its own in a service that scales by itself. The
 * containers are handed over as {@link Instance} so that a module is assembled only where it is
 * served.
 *
 * <p>To add a module: write its package with the same shape as the others, and add one line
 * here.
 */
public class AppContainer {

  @Produces
  @Singleton
  List<AppModule> modules(Instance<LotesContainer> lotes, Instance<GiftcardsContainer> giftcards) {
    return List.of(
        AppModule.of(LotesContainer.MODULE, LotesContainer.API, () -> lotes.get().eventx()),
        AppModule.of(GiftcardsContainer.MODULE, GiftcardsContainer.API, () -> giftcards.get().eventx()));
  }

  // --- shared by every module: stateless, and with nothing of any domain in them -------------

  @Produces
  @Singleton
  Clock clock() {
    return Clock.systemUTC();
  }

  @Produces
  @Singleton
  EventCodec eventCodec() {
    return new EventCodec();
  }
}
