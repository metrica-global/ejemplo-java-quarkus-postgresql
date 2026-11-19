package com.zonacards.kernel;

/** Requested page. Out-of-range values are normalised instead of rejected. */
public record PaginationOptions(int page, int pageSize) {

  public static final int DEFAULT_PAGE_SIZE = 20;
  public static final int MAX_PAGE_SIZE = 100;

  public static PaginationOptions of(Integer page, Integer pageSize) {
    int p = page == null || page < 1 ? 1 : page;
    int s = pageSize == null || pageSize < 1 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
    return new PaginationOptions(p, s);
  }

  public long offset() {
    return (long) (page - 1) * pageSize;
  }
}
