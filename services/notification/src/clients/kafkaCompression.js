import kafkajs from "kafkajs";
import SnappyCodec from "kafkajs-snappy";

// Destructured from the DEFAULT import, not named imports: kafkajs is CommonJS
// and Node can statically detect `Kafka`/`logLevel` as named exports but NOT
// `CompressionCodecs`, so `import { CompressionCodecs } from "kafkajs"` throws
// SyntaxError at load — i.e. the service would fail to start.
const { CompressionCodecs, CompressionTypes } = kafkajs;

/**
 * Registers the Snappy codec with kafkajs.
 *
 * kafkajs ships GZIP only. Hitting a Snappy-compressed record without this is
 * not a skipped message — it is a **permanent poison pill**: the consumer throws
 * `KafkaJSNotImplemented`, restarts, re-reads the same record, throws again, and
 * the consumer group ends up `Empty`. Consumption stops dead and (with the
 * client's log level suppressed, as it used to be) silently.
 *
 * That was not hypothetical: `rpk topic produce` compresses, so hand-produced
 * test events wedged the Search indexer on 2026-08-03 while the Java services
 * consumed the very same records fine — kafka-clients has Snappy built in.
 * Registering it here removes the asymmetry: any producer's compression choice
 * is now readable by every consumer in the system.
 *
 * MSK topics default to `compression.type=producer`, i.e. the broker keeps
 * whatever the producer sent — so this is a live risk in AWS the moment anything
 * (a future service, an operator with a CLI, a Kafka Connect task) compresses.
 *
 * `kafkajs-snappy` is pure JavaScript (it depends on `snappyjs`, not the native
 * `snappy` binding), so this adds no build toolchain and no platform-specific
 * binaries to a Lambda package — the exact problem that pushed Media to Java
 * rather than `sharp`/libvips.
 *
 * Codecs live in a kafkajs-global registry, so registration is process-wide and
 * idempotent: call it from anywhere a client is built, as many times as you like.
 */
export function registerCompressionCodecs() {
  CompressionCodecs[CompressionTypes.Snappy] = SnappyCodec;
}
