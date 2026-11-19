package com.zonacards.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zonacards.errx.Errx;
import java.util.List;
import org.junit.jupiter.api.Test;

class PaginationTest {

  @Test
  void optionsAreNormalised() {
    assertEquals(new PaginationOptions(1, 20), PaginationOptions.of(null, null));
    assertEquals(new PaginationOptions(1, 20), PaginationOptions.of(0, -5));
    assertEquals(new PaginationOptions(3, 100), PaginationOptions.of(3, 5000));
    assertEquals(40, PaginationOptions.of(3, 20).offset());
  }

  @Test
  void paginatedComputesPagesAndNavigation() {
    Paginated<String> page = Paginated.of(List.of("a", "b"), new PaginationOptions(2, 2), 5);
    assertEquals(3, page.page().pages());
    assertTrue(page.hasNext());
    assertTrue(page.hasPrevious());
    assertFalse(page.empty());
    assertEquals(List.of("A", "B"), page.map(String::toUpperCase).items());

    Paginated<String> none = Paginated.of(List.of(), new PaginationOptions(1, 20), 0);
    assertTrue(none.empty());
    assertEquals(0, none.page().pages());
    assertFalse(none.hasNext());
  }

  @Test
  void typedIdsAreDistinctTypesWithTrimmedValues() {
    assertEquals("abc", LoteId.of("  abc ").value());
    assertTrue(LoteId.of(null).isEmpty());
    assertFalse(GiftCardId.newId().isEmpty());
    assertEquals("x", TenantId.of("x").toString());
  }

  @Test
  void bindAndValidateRejectsMissingBodyAndRunsValidation() {
    record Req(String name) implements Validatable {
      @Override
      public void validate() {
        if (name == null || name.isBlank()) {
          throw Validation.required("name");
        }
      }
    }
    assertThrows(Errx.class, () -> Validation.bindAndValidate((Req) null));
    Errx error = assertThrows(Errx.class, () -> Validation.bindAndValidate(new Req(" ")));
    assertEquals("name", error.details().get("field"));
    assertEquals("ok", Validation.bindAndValidate(new Req("ok")).name());
  }

  @Test
  void directUnitOfWorkRunsInline() {
    UnitOfWork uow = UnitOfWork.direct();
    assertEquals(7, uow.inTx(() -> 7));
    int[] ran = {0};
    uow.runInTx(() -> ran[0]++);
    assertEquals(1, ran[0]);
  }
}
