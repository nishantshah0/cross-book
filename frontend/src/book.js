// Both sides of the book with the house merged in: asks worst first so the best ask
// sits just above the spread, bids best first below it.
export function bookSides(book, depth = 7) {
  if (!book) return { asks: [], bids: [], deepest: 1 };
  const side = (levels, housePrice, better) => {
    const merged = levels.map((l) => ({ ...l, house: false }));
    if (housePrice != null) merged.push({ price: housePrice, house: true });
    return merged
      .sort((a, b) =>
        Number(a.price) === Number(b.price)
          ? a.house - b.house
          : better(Number(a.price), Number(b.price)),
      )
      .slice(0, depth);
  };
  const asks = side(book.asks, book.house?.ask, (a, b) => a - b).reverse(),
    bids = side(book.bids, book.house?.bid, (a, b) => b - a);
  const deepest = Math.max(
    1,
    ...[...asks, ...bids].map((level) => level.quantity || 0),
  );
  return { asks, bids, deepest };
}
