package com.zonacards.kernel;

import java.util.List;
import java.util.function.Function;

/** A page of results with its metadata. */
public record Paginated<T>(List<T> items, Page page, boolean empty) {

  public static <T> Paginated<T> of(List<T> items, PaginationOptions options, long total) {
    int size = options.pageSize();
    int pages = size > 0 ? (int) ((total + size - 1) / size) : 0;
    return new Paginated<>(List.copyOf(items), new Page(options.page(), size, total, pages), items.isEmpty());
  }

  public boolean hasNext() {
    return page.number() < page.pages();
  }

  public boolean hasPrevious() {
    return page.number() > 1;
  }

  public <R> Paginated<R> map(Function<? super T, ? extends R> mapper) {
    List<R> mapped = items.stream().<R>map(mapper).toList();
    return new Paginated<>(mapped, page, empty);
  }
}
