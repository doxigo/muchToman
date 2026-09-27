/** Vite's `?raw` import — a file's text — for a test that reads another package's source. */
declare module '*?raw' {
  const text: string;
  export default text;
}
