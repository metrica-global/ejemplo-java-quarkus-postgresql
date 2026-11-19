package com.zonacards.kernel;

/** Pagination metadata. Page numbers are 1-based. */
public record Page(int number, int size, long total, int pages) {}
