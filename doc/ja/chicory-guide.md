# Chicory 徹底解説 — 純Java製 WebAssembly ランタイムの基礎から内部実装まで

> **対象バージョン: Chicory 1.7.5**(2026年3月24日リリース)
> 本書のコード例・仕様の記述はすべて Chicory 1.7.5 のソースコード(リポジトリタグ `1.7.5`)および同梱の公式ドキュメントに基づいて検証しています。
> 対象読者: Java 経験5年以上のシニアエンジニア。Java の言語仕様・JVM・Maven/Gradle の基礎知識は前提とし、WebAssembly の事前知識は前提としません。

---

## 本書の読み方

本書は「導入 → 概念理解 → 実践 → 応用 → リファレンス → 深掘り」という習熟フローに沿って構成しています。

| 目的 | 読むべき章 |
|---|---|
| 採用可否を判断したい | 第1章 → 第6章 → 第10章 |
| とにかく動かしたい | 第2章 → 第4章 |
| 設計を理解して使いこなしたい | 第3章 → 第4章 → 第5章 → 第6章 |
| ハマったとき | 第9章(→ 必要に応じて第7章) |
| コントリビュートしたい | 第7章 → 第11章 |

各章は極力独立して読めるように書いていますが、第3章の用語(`WasmModule` / `Instance` / `Machine` / ホスト関数 など)は以降のすべての章で前提とします。

---

# 第1章 イントロダクション — なぜ「純Java製」Wasmランタイムか

## 1.1 Chicory とは何か

Chicory(チコリー)は、**JVM ネイティブの WebAssembly(Wasm)ランタイム**です。Dylibso 社が開発を主導し、Red Hat のエンジニアらも主要コントリビュータとして参加する Apache-2.0 ライセンスの OSS で、2023年9月に開発が始まり、2024年12月に 1.0.0 が、2026年3月に本書の対象である 1.7.5 がリリースされました。

最大の特徴は次の一文に尽きます。

> **ネイティブ依存も JNI も一切なしで、Wasm プログラムを実行できる。JVM が動く場所ならどこでも Wasm が動く。**

Chicory はコードベースの9割以上が Java で書かれた「ただの jar」です。`pom.xml` に依存を1行足すだけで、Rust・Go・C・Zig などで書かれ Wasm にコンパイルされたコードを、JVM のサンドボックスの中で安全に実行できます。

### そもそも WebAssembly を JVM で動かす意味

WebAssembly はブラウザ由来の技術ですが、その本質は「**ポータブルで、サンドボックス化された、低レベルのバイナリ命令フォーマット**」です。サーバサイドの文脈では次の価値を持ちます。

1. **ポリグロット**: Rust や C で書かれた既存資産(SQLite、jq、正規表現エンジン等)を、言語の壁を越えて呼び出せる。
2. **サンドボックス**: Wasm モジュールは線形メモリの外を読み書きできず、明示的に許可した機能(インポート)以外は一切実行できない。サードパーティ製プラグインや、ユーザーが投稿したコードの実行基盤に向く。
3. **決定性とポータビリティ**: 同じ `.wasm` バイナリが OS・CPU アーキテクチャを問わず同じ意味論で動く。

## 1.2 Chicory が解決する課題 — ネイティブランタイム埋め込みの「2つの摩擦」

Wasm ランタイム自体は成熟した選択肢が複数あります(V8、Wasmtime、Wasmer、WasmEdge、wazero など)。しかしこれらは C/C++/Rust/Go で書かれており、Java アプリケーションに組み込むには JNI/FFI バインディング経由になります。Chicory の公式ドキュメントは、この方式の摩擦を2点挙げています。

**摩擦1: 配布(Distribution)**
Java ライブラリ(jar/war)を配布する際、ネイティブランタイムを同梱するなら「OS × CPUアーキテクチャ」の行列すべてに対応したネイティブバイナリを含める必要があります。Linux x86_64 / aarch64、macOS、Windows、Alpine(musl)……と組み合わせは膨らみ、「jar を置けば動く」という Java 本来の配布の単純さが失われます。

**摩擦2: 実行時(Runtime)**
FFI 経由でネイティブコードを呼んだ瞬間、JVM の安全性と可観測性の外に出ます。ネイティブ側のクラッシュはプロセスごと JVM を巻き込み、ヒープダンプ・プロファイラ・デバッガといった JVM のツール群も届きません。純 JVM ランタイムであれば、メモリ安全性の保証も監視ツールもそのまま機能し続けます。

```
┌─────────────────────────────┐   ┌─────────────────────────────┐
│  JNIバインディング方式        │   │  Chicory 方式                │
│                             │   │                             │
│  Java アプリ                 │   │  Java アプリ                 │
│    │ JNI/FFI (安全性の境界)  │   │    │ ただのメソッド呼び出し   │
│    ▼                        │   │    ▼                        │
│  ネイティブ Wasm ランタイム   │   │  Chicory (jar)              │
│  (OS×Arch ごとにバイナリ)    │   │    │                        │
│    │                        │   │    ▼                        │
│    ▼                        │   │  Wasm モジュール             │
│  Wasm モジュール             │   │  (すべて JVM ヒープ内で完結) │
└─────────────────────────────┘   └─────────────────────────────┘
```

## 1.3 設計思想 — 「速さ」より「安全と単純さ」

Chicory は README で目標(Goals)と**非目標(Non-Goals)**を明文化しています。採用判断に直結するので原文の趣旨をそのまま押さえてください。

**目標:**
- 可能な限り安全であること(**性能を犠牲にしてでも安全性と単純さを優先する**、と明言)
- ネイティブコードなしで、制約の強い環境を含むあらゆる JVM 環境で Wasm を簡単に動かせること
- Wasm コア仕様の完全サポート
- Java(および他のホスト言語)との統合が容易で慣用的であること

**非目標:**
- スタンドアロンのランタイムになること
- **最速のランタイムになること**
- すべての JVM プロジェクトにとっての正解になること

つまり Chicory は「Wasmtime より速い」ことを目指していません。ネイティブランタイム + JNI の方がピーク性能で勝るケースは普通にあります。Chicory を選ぶ理由は、性能ではなく**配布の単純さ・JVM 内で完結する安全性・運用のしやすさ**です。この期待値を最初に共有しておくことが、導入プロジェクトの成否を分けます(性能特性の詳細は第6章)。

## 1.4 類似手段との比較

| 手段 | 実装言語 | 配布形態 | JNI/FFI | 特徴 |
|---|---|---|---|---|
| **Chicory** | Java | jar のみ | 不要 | 純JVM。インタプリタ+バイトコードコンパイラの2エンジン |
| wasmtime-java 等のバインディング | Rust/C++ | jar + ネイティブlib | 必要 | ピーク性能は高いが配布行列と FFI 境界の問題 |
| GraalWasm | Java (Truffle) | jar 群 | 不要 | Graal/Truffle スタック上で高速。ただし Truffle 依存が付く |
| Extism (Java SDK) | — | — | — | ランタイムというより「プラグインシステム」の枠組み。Chicory 上で動く純Java版 SDK(Extism Chicory SDK)がある |
| asmble / KWasm | Java/Kotlin | jar | 不要 | 先行研究(Prior Art)。現在はメンテが停滞 |

設計面で Chicory が「遠い親戚」と公言しているのが Go エコシステムの **wazero** です。wazero は「Go 製アプリに zero dependency で Wasm を」というコンセプトで成功しており、Chicory はその Java 版という位置づけで始まりました(FOSDEM 2025 では両プロジェクトの比較講演もあります)。

## 1.5 採用判断のための評価材料

**ライセンスとガバナンス**: Apache-2.0。GitHub の `dylibso/chicory` で開発され、Dylibso と Red Hat のエンジニアが中心。毎週火曜に公開の Office Hours(16:00–16:30 UTC)があり、Zulip チャットで質問できます。

**動作要件**(1.7.5 時点、ソースの `pom.xml` および android-tests で確認):

| 項目 | 要件 |
|---|---|
| Java バージョン | **Java 11 以上**(`maven.compiler.release=11` でビルド) |
| Android | **API 28 以上**(公式の device-tests が minSdk 28 で検証) |
| SIMD 利用時のみ | Java 21 以上(Vector API / JEP 448 依存)+ インタプリタモード限定 |
| ネイティブ依存 | なし(ランタイムコンパイラ利用時のみ ASM ライブラリが必要。これも純Java) |

**仕様準拠**: Wasm コア仕様の公式テストスイート(.wast)から JUnit テストを自動生成して CI で回しており、インタプリタ・コンパイラ・WASI それぞれのテスト結果バッジを README で公開しています。2025〜2026年のロードマップで SIMD、Tail Call、例外処理(Exception Handling)、スレッド、拡張定数式、**WasmGC、Multi-Memory** までチェック済みになっており、モダンな Wasm プロポーザルへの追従は言語非依存ランタイムとしてかなり手厚い部類です。2026年の残タスクは「Performance」です。

**採用実績**(README「Who uses Chicory?」より):

- **JRuby** — Ruby の公式パーサ Prism(C実装)を Wasm 化して同梱
- **Debezium** — Go で書いた Single Message Transformation のプラグイン実行
- **Trino** — Python UDF の実行基盤
- **Bazel** — repo rules 内での Wasm 実行
- **Apache Camel** — Wasm コンポーネント
- **Quarkus** — quarkus-chicory ほか複数の Quarkiverse 拡張
- **sqlite4j / pglite4j / jq4j / protobuf4j / quickjs4j** — SQLite・PostgreSQL・jq・protoc・QuickJS を「純Java 化」して配布するライブラリ群(quickjs4j は Microcks や Apicurio Registry が利用)
- **OPA (Open Policy Agent)** の Java SDK、OpenFeature の Go Feature Flag プロバイダ、Spotify Confidence resolver、WildFly AI Feature Pack など

「C資産の純Java化」(sqlite4j 型)と「ユーザー提供コードのサンドボックス実行」(Debezium/Trino 型)という2大ユースケースが実績として確立している、というのが採用判断上の重要情報です。


---

# 第2章 クイックスタート — 15分で「動いた」まで

この章のゴールは、コピペだけで Wasm モジュールを Java から呼び出すことです。Wasm 側のツールチェーンを一切入れなくても試せる経路(2.2)を先に用意しています。

## 2.1 依存の追加と環境要件

必要なのは **Java 11 以上**と Maven または Gradle だけです。

**Maven:**

```xml
<dependency>
  <groupId>com.dylibso.chicory</groupId>
  <artifactId>runtime</artifactId>
  <version>1.7.5</version>
</dependency>
```

**Gradle:**

```groovy
implementation 'com.dylibso.chicory:runtime:1.7.5'
```

複数の Chicory アーティファクト(`wasi`、`compiler` など。第4章参照)を使う場合は、BOM でバージョンを揃えるのが定石です。

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>com.dylibso.chicory</groupId>
      <artifactId>bom</artifactId>
      <version>1.7.5</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

## 2.2 既存の .wasm を動かす(最小構成)

まず、Chicory のテストコーパスにある「階乗を計算する Wasm モジュール」をダウンロードします。自分で Wasm をビルドする必要はありません。

```bash
curl https://raw.githubusercontent.com/dylibso/chicory/main/wasm-corpus/src/main/resources/compiled/iterfact.wat.wasm > factorial.wasm
```

これを実行する Java コードは以下です。

```java
import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.wasm.Parser;

import java.io.File;

public class QuickStart {
    public static void main(String[] args) {
        // 1. .wasm バイナリをパースして WasmModule(不変のコード表現)を得る
        var module = Parser.parse(new File("./factorial.wasm"));

        // 2. インスタンス化(実行可能な仮想マシンを起こす)
        Instance instance = Instance.builder(module).build();

        // 3. エクスポートされた関数のハンドルを取得
        ExportFunction iterFact = instance.export("iterFact");

        // 4. 呼び出す。引数・戻り値は long ベース
        var result = iterFact.apply(5)[0];
        System.out.println("5! = " + result); // => 120
    }
}
```

3行で本質を言えば「**パースして(Parser)、インスタンス化して(Instance)、エクスポート関数を呼ぶ(ExportFunction)**」です。`module` は「不活性なコード」、`instance` は「そのコードの実行時表現=実行準備の整った仮想マシン」と公式ドキュメントは説明しています。

### 戻り値が `long[]` である理由

`ExportFunction` は次のような関数型インターフェースです(runtime モジュールのソースより)。

```java
@FunctionalInterface
public interface ExportFunction {
    long[] apply(long... args) throws ChicoryException;
}
```

- **配列**なのは、Wasm の関数が多値(複数の戻り値)を返せるためです。単一値の関数なら `[0]` を取ります。
- **long** なのは、Wasm の基本4型(i32 / i64 / f32 / f64)をすべて 64bit 整数のビットパターンに詰めて受け渡す設計だからです。浮動小数点数は `Value` クラスのユーティリティで変換します。

```java
import com.dylibso.chicory.wasm.types.Value;

long raw = Value.doubleToLong(3.14);   // Java double -> Wasm f64 のビット表現
double d  = Value.longToDouble(raw);   // 逆変換
long f    = Value.floatToLong(1.5f);   // f32 も同様
float  fv = Value.longToFloat(f);
```

| Wasm 型 | Java 側の受け渡し | 変換ヘルパー |
|---|---|---|
| i32 | `long`(下位32bit) | `(int)` キャスト |
| i64 | `long` そのまま | 不要 |
| f32 | `long`(ビットパターン) | `Value.floatToLong` / `longToFloat` |
| f64 | `long`(ビットパターン) | `Value.doubleToLong` / `longToDouble` |

## 2.3 自分で Wasm モジュールを作って動かす

次に「自作コードを Wasm 化して Java から呼ぶ」経路です。ここでは Rust の例を示します(TinyGo や Zig でも同様のことができます。言語別の詳細は第5章)。

```bash
# Rust ツールチェーンに wasm ターゲットを追加
rustup target add wasm32-unknown-unknown
```

```rust
// src/lib.rs
#[no_mangle]
pub extern "C" fn add(x: i32, y: i32) -> i32 {
    x + y
}
```

```toml
# Cargo.toml(抜粋)
[lib]
crate-type = ["cdylib"]
```

```bash
cargo build --release --target wasm32-unknown-unknown
# => target/wasm32-unknown-unknown/release/<name>.wasm
```

Java 側は 2.2 と完全に同じパターンです。

```java
var module = Parser.parse(new File("target/wasm32-unknown-unknown/release/adder.wasm"));
var instance = Instance.builder(module).build();
long sum = instance.export("add").apply(2, 3)[0]; // => 5
```

もうひとつ、**Java コードの中で WAT(Wasm テキスト形式)から直接ビルドする**方法もあります。Chicory 本体には WAT パーサはまだありませんが、wabt の `wat2wasm` を Wasm 化した `wabt` モジュール(それ自身が Chicory 上で動く、という自己ホスト的な構成)が公式提供されています。ユニットテストで重宝します。

```xml
<dependency>
  <groupId>com.dylibso.chicory</groupId>
  <artifactId>wabt</artifactId>
  <version>1.7.5</version>
</dependency>
```

```java
import com.dylibso.chicory.wabt.Wat2Wasm;

var wasm = Wat2Wasm.parse(
    "(module (func (export \"add\") (param $x i32) (param $y i32) (result i32)"
    + " (i32.add (local.get $x) (local.get $y))))");

var instance = Instance.builder(Parser.parse(wasm)).build();
long result = instance.export("add").apply(1, 41)[0]; // => 42
```

## 2.4 初期のハマりどころ

**(1) `UnlinkableException` / インポート未解決**
モジュールが関数やメモリを import している場合、インスタンス化時にすべて解決されている必要があります。素の `Instance.builder(module).build()` で失敗したら、そのモジュールはホスト関数(第4章)か WASI(4.5節)を要求しています。`wasm-objdump`(wabt)や後述の wasm-tools でモジュールの import セクションを確認してください。

**(2) エクスポート名が見つからない**
`instance.export("名前")` の名前は Wasm 側のエクスポート名と完全一致が必要です。Rust なら `#[no_mangle]` を忘れるとマングリングされた名前になります。

**(3) 型の食い違い**
`apply(5)` は i32 として解釈されるとは限りません。シグネチャは Wasm 側の宣言が正であり、Java 側はすべて `long` で渡すだけです。f32/f64 を「数値のまま」渡すと壊れます(必ず `Value.floatToLong` 等でビット変換)。

**(4) WASI モジュールをそのまま動かそうとする**
`wasm32-wasip1` ターゲットでビルドしたバイナリや、C を Emscripten/wasi-sdk でビルドしたバイナリは、`wasi_snapshot_preview1` 名前空間のインポートを要求します。この場合は 4.5 節の `WasiPreview1` を接続してください。


---

# 第3章 コアコンセプト — Chicory のメンタルモデル

この章が本書全体の土台です。以降の章はすべてここで定義する用語と全体像を前提に書きます。

## 3.1 全体アーキテクチャ

Chicory は少数のモジュール(Maven アーティファクト)に責務を分割しています。リポジトリ同梱の開発者向けドキュメント(AGENT.md)に依存グラフが明記されているので、それを図にします。

```
 wasm-corpus (テスト用リソース)
 wasm   ……… バイナリパーサ / バリデータ / 型定義
   └── runtime ……… インタプリタ / Instance / Store
         ├── wasi    ……… WASI Preview 1 ホスト関数群
         │     └── wasm-tools ……… wat2wasm 等(WASI 経由で動作)
         ├── compiler ……… Wasm → JVM バイトコードコンパイラ
         ├── simd    ……… SIMD 命令(差し替え可能な Machine)
         └── log     ……… ロギング抽象
 その他: annotations / annotations-processor / build-time-compiler /
         chicory-compiler-maven-plugin / dircache(-experimental) / wabt / cli
```

処理の流れを1枚にすると次のようになります。

```
 .wasm バイナリ
      │  Parser.parse()          ←─ wasm モジュール
      ▼
 WasmModule(不変・スレッド共有可)
      │  Instance.builder(module)
      │    .withImportValues(...)   ← ホスト関数 / WASI を注入
      │    .withMachineFactory(...) ← 実行エンジンを選択
      │    .build()
      ▼
 Instance(実行状態: メモリ・テーブル・グローバル)
      │  instance.export("f")
      ▼
 ExportFunction.apply(args...) ──► Machine.call() ──► 結果 long[]
                                     ▲
                     InterpreterMachine(既定)/ コンパイラ生成コード / SimdInterpreterMachine
```

## 3.2 中心となる抽象概念と責務

| 概念 | クラス | 責務 | ミュータビリティ |
|---|---|---|---|
| モジュール | `WasmModule` | パース済みバイナリの構造(型・関数・インポート/エクスポート宣言等) | 不変 |
| パーサ | `Parser` | `.wasm` → `WasmModule`。既定で検証(validation)も実施 | — |
| インスタンス | `Instance` | モジュールの実行時表現。メモリ・テーブル・グローバル・GC参照を保持 | 可変(実行状態) |
| エクスポート関数 | `ExportFunction` | `long[] apply(long...)` の関数ハンドル | — |
| インポート値 | `ImportValues` | インスタンス化時に注入する関数・グローバル・メモリ・テーブルの束 | ビルダーで構築 |
| ホスト関数 | `HostFunction` | Java で実装した、ゲストから呼べる関数 | — |
| ストア | `Store` | 複数インスタンスの名前付き管理とリンク | 可変・**非スレッドセーフ** |
| メモリ | `Memory`(IF)/ `ByteBufferMemory` / `ByteArrayMemory` | Wasm 線形メモリの JVM 上の表現 | 可変 |
| マシン | `Machine` | 実行エンジンの抽象。`long[] call(int funcId, long[] args)` の1メソッド | — |

設計上の勘所は2つあります。

**「コード」と「状態」の分離** — `WasmModule` は不変で、複数の `Instance` から共有できます。同じモジュールを大量にインスタンス化するプラグインシステム(リクエストごとに使い捨てインスタンス、など)では、パースを1回で済ませてインスタンス化だけ繰り返すのが基本形です。

**`Machine` という1メソッドの抽象** — 実行エンジン全体を `long[] call(int funcId, long[] args)` という関数型インターフェース1個に押し込めています。これが「エンジン差し替え」の接点で、インタプリタ・ランタイムコンパイラ・ビルド時コンパイラ・SIMD 対応インタプリタがすべてこの口で交換されます(3.3節)。

## 3.3 2つ(正確には3つ)の実行エンジン

Chicory は当初インタプリタ専用でしたが、2024年に「インタプリタとコンパイラを別エンジンに分離する」設計変更を行い、以後は `Machine` の差し替えで実行方式を選べます。公式ドキュメントの整理をそのまま示します。

| モード | 性能 | 動的モジュールロード | 追加要件 | 出力形態 | 適する場面 |
|---|---|---|---|---|---|
| **インタプリタ**(既定) | 遅い | ○ | なし | なし(完全解釈実行) | 既定。最大のポータビリティ。開発時、動的ロードが必要な環境 |
| **ランタイムコンパイル** | 速い | ○ | ASM 依存+リフレクション | メモリ上の Java バイトコード | 性能が欲しく、動的ロードも必要な場合 |
| **ビルド時コンパイル** | 速い | ×(静的のみ) | Maven/Gradle プラグイン | `.class` ファイル | 本番・静的モジュール。native-image 併用時の本命 |

重要な事実として、**ランタイムコンパイラもビルド時コンパイラも「インタプリタと同じ spec テストを 100% パスするドロップイン代替」**であることが公式に謳われています。つまり意味論は同じで、変わるのは速度と制約だけです。使い分けの実務は第4章(4.6/4.7)と第6章で扱います。

## 3.4 メモリモデル — Wasm 線形メモリの JVM 上の表現

Wasm のメモリは「**線形メモリ**」と呼ばれる、0番地から始まる1本の連続したバイト列です。ページ単位(1ページ = 64KiB)で確保・拡張されます。Chicory の `Memory` インターフェース(runtime モジュール)には次の定数が定義されています。

```java
public interface Memory {
    /** WebAssembly のページサイズは 64KiB = 65,536 バイト */
    int PAGE_SIZE = 65536;

    /**
     * ランタイムが許容する最大ページ数。
     * Wasm 仕様は 2^16 ページまで許すが、JVM の配列サイズ上限に基づき制限する。
     * この値は Integer.MAX_VALUE / PAGE_SIZE。
     */
    int RUNTIME_MAX_PAGES = 32767;
    ...
}
```

ここに Chicory の設計判断が凝縮されています。

- Wasm 仕様上の上限は 65,536 ページ(= 4GiB)ですが、Chicory は線形メモリを **JVM の配列/ByteBuffer で表現する**ため、`Integer.MAX_VALUE` バイトの壁から **32,767 ページ(約 2GiB)が上限**になります。巨大メモリを要求するモジュールを扱う場合はこの制約を最初に確認してください。
- 実装は2種類あり、既定は `ByteBufferMemory` です(`Instance` のビルド処理で `memoryFactory` 未指定時に `ByteBufferMemory::new` が使われることをソースで確認できます)。1.1.0 以降は配列ベースで最適化された `ByteArrayMemory` もあり、公式は「最近の OpenJDK では `ByteArrayMemory` を推奨、Android などそれ以外の VM では `ByteBufferMemory` を維持」としています(4.9節・第6章)。

ゲストとの複合データのやり取りは「ポインタ(= 線形メモリ上のオフセット)+長さ」を整数で渡し、ホスト側が `Memory` の `readString` / `writeString` / `readBytes` / `write` / `readI32` / `writeI32` 等で読み書きする、というのが基本プロトコルです(第4章 4.3)。

## 3.5 サンドボックス境界 — どこまでが「安全」か

Chicory を使う上で最も重要なメンタルモデルがこれです。

```
┌───────────────────────── JVM プロセス ─────────────────────────┐
│                                                               │
│   ホスト(あなたの Java コード)                                 │
│   ├─ ファイル・ネットワーク・スレッド…なんでもできる             │
│   │                                                           │
│   │   ┌────────── サンドボックス(ゲスト)──────────┐           │
│   │   │  Wasm モジュール                            │           │
│   │   │  ・自分の線形メモリしか読み書きできない       │           │
│   │   │  ・I/O 手段を持たない(純粋な計算のみ)        │           │
│   │   │  ・外界への唯一の窓 = インポート             │           │
│   │   └──────────────┬─────────────────────────────┘           │
│   │                  │ インポート解決                           │
│   └── ホスト関数 ◄────┘   ←←← ここが唯一の、そして重大な境界      │
└───────────────────────────────────────────────────────────────┘
```

公式ドキュメント(Host Functions)の要点はこうです。

- インポートを持たない Wasm モジュールは「**純粋な計算**」であり、いかなる I/O も、他モジュールとの相互作用もできません。
- インポートを **ホスト関数**(Java で書いた関数)で満たすと、ゲストはそれを呼べるようになります。ホスト関数は**無制限**で、周囲の環境と任意のやり方で相互作用できます。つまり**サンドボックスからの実質的な脱出口**です。
- したがって、**ホスト関数はセキュリティ境界**であり、信頼できない Wasm コードを動かすなら慎重に実装する必要があります。感覚としては OS のシステムコールを自分で定義しているのに近い、というのが公式の説明です。

WASI(4.5節)も実体は「あらかじめ用意されたホスト関数の詰め合わせ」にすぎず、しかもすべて**仮想化**されています(ゲストはホストの資源に直接触れず、WASI 層が仲介し、その表面積は設定で絞れます)。

なお「CPU を使い潰す無限ループ」はメモリ安全とは別の問題です。Chicory の実行はキャリアスレッドの**割り込み(interrupt)を尊重**するので、`ExecutorService` + タイムアウトで絶対時間の上限を課すのが定石です(第6章 6.5)。

## 3.6 用語集

| 用語 | 意味 |
|---|---|
| ゲスト (guest) | 実行される Wasm モジュール(インスタンス)側 |
| ホスト (host) | Chicory を組み込んだ Java アプリケーション側 |
| 線形メモリ | ゲストが読み書きできる唯一のメモリ空間。64KiB ページ単位 |
| インポート / エクスポート | モジュールが要求する外部機能 / モジュールが外に公開する機能。関数・メモリ・テーブル・グローバルの4種 |
| ホスト関数 | インポートを満たすために Java で実装する関数 |
| トラップ (trap) | Wasm 実行時の致命的エラー(ゼロ除算、範囲外メモリアクセス等)。Chicory では `TrapException` になる |
| WASI | WebAssembly System Interface。stdio・ファイル・時計・乱数などの標準ホスト関数仕様。Chicory は Preview 1 をサポート |
| WAT | Wasm のテキスト表現(S式)。`.wat` ⇔ `.wasm` は wat2wasm 等で相互変換 |
| Machine | Chicory の実行エンジン抽象。インタプリタ/コンパイラを差し替える接点 |
| validation | インスタンス化前の型検査。Wasm 仕様のアルゴリズムに基づく。既定で有効 |


---

# 第4章 基本機能ガイド

各節は「目的 → 基本コード → 動作の仕組み → 注意点」の順で統一しています。API 詳細は第8章、エラー時は第9章を参照してください。

## 4.1 モジュールのロードとパース

**目的**: `.wasm` バイナリを `WasmModule` に変換する。

**基本コード**: `Parser` は入力ソース別に static メソッドを持ちます。

```java
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;

WasmModule m1 = Parser.parse(new File("mod.wasm"));
WasmModule m2 = Parser.parse(Path.of("mod.wasm"));
WasmModule m3 = Parser.parse(Files.readAllBytes(Path.of("mod.wasm"))); // byte[]
WasmModule m4 = Parser.parse(getClass().getResourceAsStream("/mod.wasm")); // InputStream
```

**仕組み**: パースと同時に、Wasm 仕様の検証アルゴリズムに基づく validation(`Validator`)が走ります。ここで型の不整合などは `InvalidException`、バイナリ形式の破損は `MalformedException` として弾かれます(第9章)。

**注意点**: `Parser.builder().withValidation(false)` で検証を切ることもできますが、信頼済みかつ検証済みのモジュールを大量に再ロードする場合の最適化手段であり、通常は推奨されません。特に SIMD は「validation 必須。無効化すると誤った結果を生む可能性が高い」と公式が明記しています。

## 4.2 インスタンス化と関数呼び出し

**目的**: `WasmModule` から実行状態を作り、関数を呼ぶ。

**基本コード**:

```java
Instance instance = Instance.builder(module)
        .withImportValues(imports)      // インポートがある場合
        .withMachineFactory(factory)    // エンジンを変える場合(既定はインタプリタ)
        .build();

ExportFunction f = instance.export("myFunc");
long[] results = f.apply(arg1, arg2);
```

`Instance.Builder` の主なオプション(1.7.5 のソースで確認できるもの):

| メソッド | 用途 |
|---|---|
| `withImportValues(ImportValues)` | ホスト関数等の注入(4.4) |
| `withMachineFactory(Function<Instance,Machine>)` | 実行エンジン選択(4.6/4.7/4.9) |
| `withMemoryLimits(MemoryLimits)` | メモリ上限の強制(6.3) |
| `withMemoryFactory(Function<MemoryLimits,Memory>)` | メモリ実装の差し替え(6.3) |
| `withTableFactory` / `withGlobalFactory` | テーブル/グローバル生成のカスタマイズ |
| `withInitialize(boolean)` / `withStart(boolean)` | 初期化・start 関数実行の制御 |
| `withUnsafeExecutionListener(ExecutionListener)` | 命令単位のフック(6.5。名前どおり unsafe) |

**仕組み**: `build()` 時にインポート解決 → メモリ/テーブル/グローバルの生成 → データ/エレメントセグメントの初期化 → (あれば)start 関数の実行、という Wasm 仕様どおりのインスタンス化が行われます。

**注意点**: WASI の「コマンド型」モジュールは**インスタンス化した瞬間に `_start` が走る**、すなわちインスタンス化=プログラム実行です(4.5)。この挙動を制御したいときに `withStart(false)` 等が効きます。

## 4.3 メモリ経由の複合データ受け渡し

**目的**: 文字列やバイト列など、i32/i64/f32/f64 に収まらないデータをゲストとやり取りする。

Wasm が理解するのは基本的な整数・浮動小数点数だけなので、複雑な型の受け渡しは「低レベルのポインタ渡し」になります。公式の count_vowels(Rust 製、文字列中の母音を数える)の例をそのまま示します。

```java
Instance instance = Instance.builder(Parser.parse(new File("./count_vowels.wasm"))).build();
ExportFunction countVowels = instance.export("count_vowels");

// モジュールが公開しているアロケータを使う
ExportFunction alloc   = instance.export("alloc");
ExportFunction dealloc = instance.export("dealloc");

Memory memory = instance.memory();
String message = "Hello, World!";
int len = message.getBytes().length;

int ptr = (int) alloc.apply(len)[0];   // ゲスト内メモリを確保 → ポインタが返る
memory.writeString(ptr, message);      // ホストから線形メモリに書き込む

long result = countVowels.apply(ptr, len)[0]; // ポインタ+長さで呼ぶ
dealloc.apply(ptr, len);                      // ゲスト側の割り当てを解放
// result == 3  ("Hello, World!" の母音は e,o,o の3つ)
```

**仕組み**: `instance.memory()` が返す `Memory` はゲストの線形メモリそのものです。`writeString`/`readString` は UTF-8 が既定で、`Charset` 指定のオーバーロードもあります。

**注意点**:

- **メモリの所有者はゲスト**です。どの領域が空いているかを知っているのはゲスト側のアロケータだけなので、この例のように `alloc`/`dealloc` をエクスポートしてもらうのが安全な作法です(勝手なオフセットに書くと、ゲストのヒープを破壊します)。
- 確保した領域の解放漏れは、そのインスタンスが生きている限りゲスト内リークになります。短命インスタンス(使い捨て)戦略ならリークごと破棄できます。

## 4.4 ホスト関数 — ゲストに Java の機能を与える

**目的**: モジュールのインポートを Java 実装で満たす。

**基本コード**: `console.log` というインポートを要求するモジュールに、標準出力へのログ関数を与える公式例です。

```java
import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.wasm.types.FunctionType;
import com.dylibso.chicory.wasm.types.ValType;

var func = new HostFunction(
    "console",                    // インポートの名前空間(モジュール名)
    "log",                        // 関数名
    FunctionType.of(
        List.of(ValType.I32, ValType.I32),  // 引数: (len, offset)
        List.of()                            // 戻り値: なし
    ),
    (Instance instance, long... args) -> {
        var len = (int) args[0];
        var offset = (int) args[1];
        var message = instance.memory().readString(offset, len);
        System.out.println(message);
        return null;   // 戻り値なしの場合は null
    });
```

インスタンス化時に渡す方法は2つあります。

```java
// (a) ImportValues を直接組む
var imports = ImportValues.builder().addFunction(func).build();
var instance = Instance.builder(module).withImportValues(imports).build();

// (b) Store 経由(複数モジュールを扱うならこちら。4.8参照)
var store = new Store();
store.addFunction(func);
var instance2 = store.instantiate("logger", module);
```

**仕組み**: ホスト関数の必要3要素は「①インポートの名前空間と関数名、②Wasm の型シグネチャ、③呼ばれたときに実行するラムダ」です。ラムダには呼び出し元の `Instance` が渡ってくるので、この例のように**ゲストのメモリを読んで**文字列を復元する、というポインタ渡しプロトコルの受け側を書けます。

**注意点**: 3.5節の通り、ホスト関数はサンドボックスの脱出口=セキュリティ境界です。信頼できないゲストに与えるホスト関数では、引数(特にポインタと長さ)の検証、副作用の最小化、例外の扱いに注意してください。

## 4.5 WASI Preview 1 — 標準的なシステムインターフェース

**目的**: `wasi_snapshot_preview1` を要求するモジュール(Rust の `wasm32-wasip1`、TinyGo の `-target=wasi` 等でビルドしたもの)を動かす。

WASI はゲストがインポートできる「システム機能のホスト関数スイート」で、stdin/stdout/stderr、環境変数、コマンドライン引数、システムクロック、乱数、基本的なファイル読み書き(**仮想ファイルシステム経由**)を提供します。すべて仮想化されており、ゲストはホスト資源に直接触れません。

**依存**:

```xml
<dependency>
  <groupId>com.dylibso.chicory</groupId>
  <artifactId>wasi</artifactId>
  <version>1.7.5</version>
</dependency>
```

**基本コード**(標準入出力をメモリ上のストリームに差し替え、Rust 製の「stdin の名前に挨拶する」モジュールを動かす公式例):

```java
import com.dylibso.chicory.wasi.WasiOptions;
import com.dylibso.chicory.wasi.WasiPreview1;
import com.dylibso.chicory.runtime.Store;

var fakeStdin  = new ByteArrayInputStream("Chicory".getBytes());
var fakeStdout = new ByteArrayOutputStream();
var fakeStderr = new ByteArrayOutputStream();

var wasiOpts = WasiOptions.builder()
        .withStdout(fakeStdout)
        .withStderr(fakeStderr)
        .withStdin(fakeStdin)
        .build();

var wasi = WasiPreview1.builder().withOptions(wasiOpts).build();

var store = new Store().addFunction(wasi.toHostFunctions());
// WASI コマンド型モジュールはインスタンス化と同時に _start が実行される
store.instantiate("greeter", Parser.parse(new File("greet-wasi.wasm")));

assert fakeStdout.toString().equals("Hello, Chicory!");
```

システムの実ストリームに繋ぐ短縮形も用意されています。

```java
var wasiOpts = WasiOptions.builder().inheritSystem().build();
```

**主なオプション**:

| オプション | 効果 |
|---|---|
| `withStdin/withStdout/withStderr` | 標準入出力の接続(Java の InputStream/OutputStream) |
| `inheritSystem()` | System.in/out/err にまとめて接続 |
| `withArguments(List.of("prog", "--flag"))` | argv。先頭は実行ファイル名相当 |
| `withEnvironment("KEY", "value")` | 環境変数(複数回呼べる) |
| `withDirectory(guestPath, hostPath)` | ディレクトリをゲストに見せる(プリオープン) |

**仕組みと注意点**:

- WASI コマンド型モジュールは仕様により暗黙に `_start` を呼ぶため、「インスタンス化=実行」です。
- ディスク操作は限定サポートで、公式は**仮想ファイルシステム(Google Jimfs)上でのみテストしており、同じ構成を推奨**しています。実ディレクトリを直接見せるのではなく、Jimfs に必要なファイルだけコピーして `withDirectory` で渡すのが公式の例です。サンドボックスの観点でもこの方式が安全です。
- 未サポートの WASI 関数をゲストが呼ぶと `WasmRuntimeException` が投げられます。1.7.5 時点の対応表では大半の `fd_*`/`path_*` は対応済みで、**ソケット系(`sock_accept`/`sock_recv`/`sock_send`)と `fd_fdstat_set_rights` は未対応**、`clock_time_get` は CPU 時間系クロック ID 非対応、`path_symlink` はダングリングシンボリックリンク非対応、といった制限があります(全表は公式ドキュメント usage/wasi 参照)。ネットワークを使うゲストは基本的に動かない、と考えてください。

## 4.6 ランタイムコンパイラ — その場で JVM バイトコードへ

**目的**: インタプリタのまま意味論を変えずに実行速度を上げる。

**依存とコード**:

```xml
<dependency>
  <groupId>com.dylibso.chicory</groupId>
  <artifactId>compiler</artifactId>
  <version>1.7.5</version>
</dependency>
```

```java
import com.dylibso.chicory.compiler.MachineFactoryCompiler;

var module = Parser.parse(new File("your.wasm"));
var instance = Instance.builder(module)
        .withMachineFactory(MachineFactoryCompiler::compile)
        .build();
```

**仕組み**: Wasm 命令列を**その場でメモリ上の Java バイトコードに変換**します。現状は全関数を先行(eager)コンパイルするため、インスタンス初期化時に小さなペナルティを払い、実行時のスピードアップで回収するモデルです。インタプリタと同じ spec テストを 100% パスします。

**注意点**:

- ASM への依存と**リフレクション+動的クラスロード**が入ります。標準的な JVM では問題ありませんが、Android や GraalVM native-image では動かないか追加設定が要ります(その場合は 4.7 のビルド時コンパイルへ)。
- **JVM のメソッドサイズ上限**(64KiB)を超える巨大な Wasm 関数はコンパイルできず、その関数だけインタプリタにフォールバックします。既定の `InterpreterFallback.WARN` では標準エラーに `Warning: using interpreted mode for WASM function index: 232` のような警告が出ます。制御は次の通りです。

```java
import com.dylibso.chicory.compiler.InterpreterFallback;

var instance = Instance.builder(module)
        .withMachineFactory(
            MachineFactoryCompiler.builder(module)
                .withInterpreterFallback(InterpreterFallback.SILENT) // WARN / SILENT / FAIL
                .compile())
        .build();

// あるいは、対象関数を明示指定(コンパイル時間も短縮できる)
MachineFactoryCompiler.builder(module)
        .withInterpretedFunctions(Set.of(232, 251))
        .compile();
```

`FAIL` にすると巨大関数があった時点で例外になります(「絶対に解釈実行させたくない」場合)。関数インデックスの一覧は、一度 `WARN` で動かして収集するのが公式推奨の手順です。

- 実験的機能として**コンパイル結果のキャッシュ**があります(`dircache-experimental` アーティファクト)。モジュールの SHA-256 ダイジェストをキーに、コンパイル済みバイトコードの jar をディレクトリに保存し、次回以降のコンパイルをスキップして起動を速くします。ファイルシステムのアトミック move を使うためスレッド間・プロセス間で共有可能ですが、**エビクション(自動削除)はない**ので、ディスクは自分で管理します。

## 4.7 ビルド時コンパイル — Wasm を .class にして同梱する

**目的**: 実行時コンパイルのコストとリフレクションを排除し、最高の起動特性と native-image 互換性を得る。

**仕組みと利点**(公式ドキュメントの列挙):

- 変換がビルド時に済むため**インスタンス初期化が速い**
- **リフレクション不要** → native-image で扱いやすい
- 実行時依存が減る(ASM はビルド時のみ)
- **Wasm モジュールを自己完結の jar として配布できる** — 「元々 Java 向けでないソフトウェアを Java プラットフォームで配布する便利な手段」。sqlite4j などの「純Java化」ライブラリ群はまさにこれです

**Maven プラグイン設定**:

```xml
<plugin>
  <groupId>com.dylibso.chicory</groupId>
  <artifactId>chicory-compiler-maven-plugin</artifactId>
  <executions>
    <execution>
      <id>compiler-gen</id>
      <goals><goal>compile</goal></goals>
      <configuration>
        <wasmFile>src/main/resources/add.wasm</wasmFile>
        <name>org.acme.wasm.Add</name>
      </configuration>
    </execution>
  </executions>
</plugin>
```

生成されたクラスは `load()`(WasmModule の取得)と `create(Instance)`(Machine ファクトリ)を持ち、次のように使います。

```java
var instance = Instance.builder(Add.load())
        .withMachineFactory(Add::create)
        .build();
```

さらに `moduleInterface` パラメータを足すと、モジュールのエクスポート/インポートに対応した**型付き Java ラッパー**(`Demo_ModuleExports` / `Demo_ModuleImports`)も生成され、`long[]` の手動マッピングから解放されます(アノテーションプロセッサ方式より簡単、と公式が案内しています)。Gradle プラグインと CLI も同じ機能を提供します。

**注意点**: 動的ロードは不可(ビルド時に `.wasm` が確定している必要がある)。巨大関数は**既定でビルド失敗**します(ランタイムコンパイラの WARN と違い FAIL がデフォルト)。エラーメッセージ中の関数インデックスを `interpreterFallback` = WARN で収集し、明示リストを設定するのは 4.6 と同じ流儀です。

## 4.8 Store とリンク — 複数モジュールの合成

**目的**: 複数のモジュール/ホスト関数を名前で管理し、モジュール間のインポート/エクスポートを繋ぐ。

`Store` は Wasm 仕様の store 概念に対応する中間レベルの抽象で、関数・グローバル・メモリ・テーブルを**名前付きで**収集します。

```java
var store = new Store();
store.addFunction(consoleLogFunc);                    // 単発のホスト関数登録
var logger = store.instantiate("logger", loggerModule); // 名前付きインスタンス化
```

`store.instantiate("logger", ...)` は、次の低レベル操作の短縮形であることが公式に明記されています。

```java
var imports = store.toImportValues();
var instance = Instance.builder(m).withImportValues(imports).build();
store.register("logger", instance);
```

名前付きで登録されたインスタンスのエクスポートは、**`logger.logIt` のように修飾されて他のインスタンスから見える**ようになります。これがモジュール間リンクの基本メカニズムです。

**注意点**(公式の Notes をそのまま):

- 同名で再登録すると、一致する名前の関数等は**上書き**されます。
- `Store` は**ミュータブルで、スレッドセーフではなく、共有を想定しない**オブジェクトです。
- **モジュール間の依存関係を自動解決しません**。依存の順にインスタンス化・登録するのは利用者の責務です。

## 4.9 その他の基本機能

**ロギング** — 既定では外部依存を避けるため **JDK Platform Logging(JEP 264)**を使います。`java.util.logging.config.file` で設定でき、slf4j や log4j2 の JEP 264 アダプタも利用可能。それでも合わなければ `com.dylibso.chicory.log.Logger` を自前実装して差し込めます(`SystemLogger` が標準実装)。

**SIMD** — `simd` アーティファクトを追加し、Machine を差し替えます。

```java
import com.dylibso.chicory.simd.SimdInterpreterMachine;

var instance = Instance.builder(module)
        .withMachineFactory(SimdInterpreterMachine::new)
        .build();
```

制約は「**Java 21+ かつインタプリタモードのみ**」(Vector API / JEP 448 を使うため)。validation 必須です。

**メモリ実装の選択** — 1.1.0 以降、最適化された `ByteArrayMemory` が使えます。

```java
import com.dylibso.chicory.runtime.ByteArrayMemory;

var instance = Instance.builder(module)
        .withMemoryFactory(ByteArrayMemory::new)
        .build();
```

公式の指針は「**最近の OpenJDK では ByteArrayMemory 推奨、Android などそれ以外の VM では既定の ByteBufferMemory を維持**」「変更はベンチマークと分析を伴わずに行わないこと」です。

**モダンプロポーザル対応** — 1.7.5 時点で、Tail Call・例外処理・スレッド(shared memory は `MemoryLimits` の `shared` フラグとしてモデル化)・拡張定数式・**WasmGC**・**Multi-Memory** がロードマップ上チェック済みです。GC オブジェクトはランタイム内で `WasmStruct` / `WasmArray` / `WasmI31Ref` として表現されます(内部構造は第7章)。SIMD のみ上記の Java 21+/インタプリタ限定という条件付きです。


---

# 第5章 実践レシピ — 「やりたいこと」から引く

第4章が機能単位だったのに対し、この章はユースケース単位です。

## 5.1 言語別: ゲストモジュールのビルドと呼び出し

### Rust

| 用途 | ターゲット | 備考 |
|---|---|---|
| 純粋な計算ライブラリ | `wasm32-unknown-unknown` | インポートなしの「純粋計算」を作りやすい |
| CLI 的プログラム / std 利用 | `wasm32-wasip1` | WASI 必須(4.5) |

```bash
rustup target add wasm32-wasip1
cargo build --release --target wasm32-wasip1
```

文字列受け渡しには 4.3 の `alloc`/`dealloc` パターンをエクスポートしておくと Java 側が楽になります。

### Go (TinyGo)

本家 Go の Wasm サポートは JS 環境前提の部分が多いため、ライブラリ的な用途では **TinyGo** が定番です。

```go
//export add
func add(x, y int32) int32 { return x + y }

func main() {} // TinyGo では必要
```

```bash
tinygo build -o add.wasm -target=wasi ./main.go
```

`-target=wasi` なので Java 側は `WasiPreview1` の接続が必要です(4.5)。Debezium の Go 製 SMT プラグインや、OpenFeature の Go Feature Flag 評価がこの構成の実例です。

### C / C++

wasi-sdk(clang)でビルドするのが素直です。sqlite4j のように「著名な C ライブラリを Wasm 化して純Java ライブラリとして配布する」パターンの土台になります。

```bash
$WASI_SDK/bin/clang --target=wasm32-wasip1 -O2 -o lib.wasm lib.c \
    -Wl,--export=my_func -Wl,--export=malloc -Wl,--export=free
```

`malloc`/`free` をエクスポートしておくと、4.3 のメモリ受け渡しにそのまま使えます。

### Zig

```bash
zig build-exe lib.zig -target wasm32-wasi -O ReleaseSmall
```

Zig は Wasm バイナリが小さく出る傾向があり、配布サイズに効きます。

**共通の勘所**: どの言語でも、Java 側から見える世界は「エクスポート関数 + 線形メモリ」だけです。言語ごとの違いは(a)どの WASI 機能を暗黙に要求するか、(b)メモリ確保関数を何という名前でエクスポートするか、の2点に集約されます。まず `wasm-objdump -x`(wabt)や wasm-tools で import/export セクションを眺める癖をつけてください。

## 5.2 プラグインシステムの構築 — 信頼できないコードを安全に動かす

Chicory の代表ユースケースです。設計の骨子:

1. **モジュールは1回パース、インスタンスは使い捨て** — `WasmModule` は不変なのでキャッシュし、実行のたびに `Instance` を作って捨てる。状態の持ち越し・リークを構造的に防げます。
2. **ホスト関数 API を最小に設計** — プラグインに見せる機能(3.5節のセキュリティ境界)を明示的に列挙。WASI を渡すなら `WasiOptions` を空に近い構成にし、ディレクトリは Jimfs 経由の仮想 FS だけを見せる。
3. **リソース制限** — `withMemoryLimits` でメモリ上限、`ExecutorService` + タイムアウト + interrupt で CPU 時間上限(6.5)。
4. **エラーの封じ込め** — `ChicoryException` を捕捉してプラグイン単位の失敗に留める(5.6)。

```java
// 概形
public final class PluginHost {
    private final WasmModule module;          // キャッシュ(スレッド共有可)
    private final ExecutorService pool = Executors.newCachedThreadPool();

    public byte[] run(byte[] input, Duration timeout) throws Exception {
        var future = pool.submit(() -> {
            var instance = Instance.builder(module)
                    .withImportValues(minimalImports())
                    .withMemoryLimits(new MemoryLimits(1, 64)) // 例: 最大64ページ=4MiB
                    .build();
            // …input を書き込み、実行し、結果を読む(4.3)
            return output;
        });
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);   // interrupt が Wasm 実行を停止させる
            throw e;
        }
    }
}
```

Extism Chicory SDK は、この「プラグインシステムの枠組み」をプロトコルごと提供する上位レイヤなので、独自設計の前に一度検討する価値があります。

## 5.3 「C資産の純Java化」レシピ — sqlite4j パターン

sqlite4j / jq4j / protobuf4j / quickjs4j が実証しているパターンです。ビルド時コンパイラ(4.7)の「Wasm モジュールを自己完結 jar として配布する」能力が核になります。

```
[C ライブラリのソース]
   │ wasi-sdk でビルド(CI 上で1回)
   ▼
[lib.wasm] ── src/main/resources に同梱
   │ chicory-compiler-maven-plugin(goal: compile, moduleInterface 指定)
   ▼
[生成 .class 群 + 型付きラッパー]
   │ 通常の jar パッケージング
   ▼
[純Java ライブラリ jar] ← 利用者から見ればネイティブ依存ゼロの普通の依存
```

利点は第1章の「配布の摩擦」がゼロになること。JNI 版 SQLite ドライバの OS×Arch 行列問題が、jar 1個に畳み込まれます。トレードオフはネイティブ版比の実行速度で、これは第6章の計測手順で自分のワークロードに対して定量化してください。

## 5.4 フレームワーク統合

**Quarkus**: Quarkiverse に `quarkus-chicory` 拡張があり、native-image を含むビルド統合が提供されます。CEL ポリシーエンジンを Chicory で動かす公式ブログ(quarkus.io)の事例もあります。関連拡張として `quarkus-proxy-wasm`、`quarkus-quickjs4j`、`quarkus-grpc-zero` があります。

**Spring Boot(汎用 Java アプリ)**: 特別な統合は不要で、`WasmModule` をシングルトン Bean、`Instance` をリクエストスコープ(または都度生成)にするのが自然な構成です。ビルド時コンパイル + `dircache` は起動時間の改善に効きます。

**native-image**: インタプリタとビルド時コンパイルはそのまま通ります。ランタイムコンパイラはリフレクション+動的クラスロードのため原則不向きです(4.6)。

## 5.5 テストの書き方

**(1) WAT をテスト内でインラインビルド** — `wabt` モジュールの `Wat2Wasm.parse`(2.3)を使うと、テストケースごとに小さなモジュールをソース管理でき、フィクスチャの `.wasm` バイナリをリポジトリに撒かずに済みます。

```java
@Test
void addsTwoNumbers() {
    var wasm = Wat2Wasm.parse("(module (func (export \"add\") ... ))");
    var inst = Instance.builder(Parser.parse(wasm)).build();
    assertEquals(42, inst.export("add").apply(40, 2)[0]);
}
```

**(2) WASI モジュールの入出力検証** — 4.5 のように stdin/stdout/stderr を `ByteArrayInput/OutputStream` に差し替えれば、外部プロセス不要で「コマンド型」ゲストの振る舞いを丸ごと assert できます。

**(3) ゲスト言語側のビルドを CI に含めるか** — 含めると再現性は上がるがツールチェーン管理が重くなります。実務ではビルド済み `.wasm` をアーティファクトとしてバージョン管理(またはパッケージレジストリ管理)し、ダイジェストを固定するのが均衡点です。ちなみに Chicory 自身は Wasm 公式テストスイート(.wast)から JUnit を自動生成してエンジンを検証しています(第7章 7.6)。

## 5.6 エラーハンドリング設計

Chicory の例外はすべて `ChicoryException`(`RuntimeException` のサブクラス)に根を持ちます。1.7.5 のソースで確認した階層:

```
ChicoryException (RuntimeException)
├─ MalformedException        … バイナリ形式が壊れている(パース時)
├─ InvalidException          … validation 違反(型検査エラー)
├─ UnlinkableException       … インポート解決の失敗(リンク時)
├─ UninstantiableException   … インスタンス化の失敗(start でのトラップ等)
├─ TrapException             … 実行時トラップ(unreachable、範囲外アクセス等)
├─ WasmRuntimeException      … ランタイム都合のエラー(未サポート WASI 関数呼び出し等)
├─ ChicoryInterruptedException … スレッド割り込みによる実行中断
├─ ExecutionCompletedException … 実行完了の内部シグナル
└─ WasiExitException (wasi)  … ゲストの proc_exit。終了コードを保持
```

設計指針:

- **フェーズで捕まえ分ける**: パース/リンク時例外(Malformed/Invalid/Unlinkable)は「デプロイ不良」であり、起動時に fail-fast させる。実行時例外(Trap/WasmRuntime)は「その呼び出しの失敗」であり、リクエスト単位で処理する。
- **`WasiExitException` は準正常系**: WASI コマンド型ゲストの `exit(n)` はこの例外で表現されるので、捕捉して終了コードを取り出します(exit 0 の扱いに注意)。
- **タイムアウト**: interrupt 由来の `ChicoryInterruptedException` を「実行打ち切り」として明示的にハンドリングする(6.5)。
- **インスタンスを疑ったら捨てる**: トラップ後のゲスト内部状態(アロケータ等)の健全性は保証されないため、疑わしいインスタンスは再利用せず作り直すのが安全です。`WasmModule` は無傷なので再インスタンス化は安価です。

---

# 第6章 パフォーマンスと運用

## 6.1 エンジン選択 — 最初にして最大のチューニング

第3章 3.3 の3モードの選択が支配的です。定性的な指針:

```
開発中・とりあえず動かす      → インタプリタ(既定)
動的に .wasm を受け取る本番    → ランタイムコンパイラ(+ dircache で起動改善)
.wasm がビルド時に確定する本番 → ビルド時コンパイラ(最速の初期化・native-image 可)
数値計算で SIMD が効く        → SIMD インタプリタ(Java 21+)…ただしコンパイラと排他
```

「コンパイラは速い」の程度はワークロード依存です。公式も「usually expected to evaluate (much) faster」という言い方に留めており、絶対値は自分で測るのが前提です。

## 6.2 自分の環境で測る — JMH の手順

Chicory リポジトリには `jmh` モジュールがあり、`BenchmarkFactorialExecution`、`BenchmarkSievePrimes`、`BenchmarkParsing`、`BenchmarkWat2Wasm` などのベンチが同梱されています。まずこれを動かして肌感を掴み、次に**自分のモジュールと入力**で測ります。最小の自作 JMH は次の形です。

```java
@State(Scope.Benchmark)
public class MyWasmBench {
    WasmModule module;
    Instance interp;
    Instance compiled;

    @Setup
    public void setup() {
        module = Parser.parse(new File("target/my.wasm"));
        interp = Instance.builder(module).build();
        compiled = Instance.builder(module)
                .withMachineFactory(MachineFactoryCompiler::compile).build();
    }

    @Benchmark public long interpreter() { return interp.export("run").apply(N)[0]; }
    @Benchmark public long compiler()    { return compiled.export("run").apply(N)[0]; }
}
```

測るべき軸は3つ: **①スループット**(上のような呼び出しベンチ)、**②初期化時間**(`Instance.builder(...).build()` 自体をベンチ関数にする。ランタイムコンパイラの eager コンパイルコストがここに乗る)、**③メモリ実装**(`ByteBufferMemory` vs `ByteArrayMemory` を `withMemoryFactory` で切り替え。公式が「ベンチマークなしに変えるな」と言うのはここです)。

比較対象にネイティブランタイム(wasmtime 等)を含める場合は、第1章の非目標(最速は目指さない)を思い出し、「その差額で配布と運用の単純さを買うか」という判断軸で読みます。

## 6.3 メモリとリソースの制限

- **線形メモリ上限**: ランタイム上限は 32,767 ページ ≒ 2GiB(3.4)。それより細かい制御は `withMemoryLimits(new MemoryLimits(initial, max))` でインスタンス単位に課します。信頼できないゲストには必須の設定です。
- **ホスト側ヒープ**: 線形メモリは JVM ヒープ上に確保されるため、`-Xmx` の設計に「同時インスタンス数 × メモリ上限」を織り込みます。
- **アロケーション戦略**: runtime には `MemAllocStrategy`(`DefaultMemAllocStrategy` / `ExactMemAllocStrategy`)があり、メモリ成長時の確保方法を調整できます。既定で困ってから触る類のノブです。

## 6.4 巨大関数問題 — JVM メソッドサイズ上限

コンパイラ系エンジン特有の制約として、**Wasm の1関数 = JVM の1メソッド**に変換されるため、JVM のメソッドサイズ上限(64KiB バイトコード)を超える巨大な Wasm 関数はコンパイルできません。挙動は 4.6/4.7 の通り(ランタイム: WARN でフォールバック、ビルド時: 既定 FAIL)。大規模な生成コード(protobuf、パーサジェネレータ産)や -O0 ビルドのモジュールで踏みがちです。対処: ①ゲスト側を最適化ビルドにする(関数が縮む)、②該当関数を `withInterpretedFunctions` で明示指定して混成実行、③関数分割をゲスト側に依頼。

## 6.5 並行実行と CPU 制限

- **スレッド安全性の整理**: `WasmModule` は不変で共有可。`Instance` は可変状態(メモリ等)を持つため、**1インスタンスを複数スレッドから同時に叩かない**のが基本(スレッドごと/リクエストごとにインスタンスを分ける)。`Store` は明示的に非スレッドセーフ(4.8)。
- **タイムアウト**: Chicory の実行は**キャリアスレッドの interrupt を尊重**します。公式例の通り `ExecutorService` + `future.get(timeout)` + `future.cancel(true)` で絶対時間の上限を課せます。これは「信頼できないコードの停止保証」の第一選択です。
- **命令単位フック**: `withUnsafeExecutionListener` は**全命令ごと**にリスナが呼ばれる低レベル機構で、公式も「extremely risky / 使用は細心の注意を」としています。命令数カウント(fuel 的な制御)の自作に使えますが、性能への影響は甚大です。
- **Wasm threads プロポーザル**: shared memory を伴うマルチスレッド Wasm は 1.7.5 でサポート済みですが、ホスト側のスレッド供給・同期設計が絡むため、まずシングルスレッド+複数インスタンスで設計できないか検討するのが実務的です。

## 6.6 セキュリティ運用チェックリスト

| リスク | 対策 |
|---|---|
| メモリ食い潰し | `withMemoryLimits` + JVM ヒープ設計(6.3) |
| CPU 食い潰し(無限ループ) | ExecutorService + timeout + interrupt(6.5) |
| ホスト関数経由の権限昇格 | 見せる関数を最小化、引数検証、副作用の監査(3.5/5.2) |
| ファイルアクセス | WASI は Jimfs 仮想 FS のみ見せる(4.5) |
| ネットワーク | WASI のソケット系は未対応=そもそも開いていない(4.5) |
| 悪意あるバイナリ | validation を切らない(4.1)。パース例外を fail-fast で扱う(5.6) |
| サプライチェーン | .wasm のダイジェスト固定(5.5)。dircache のキーも SHA-256 ダイジェスト |


---

# 第7章 内部実装の解説 — コードのどこを読めば何がわかるか

Chicory は「シンプルで理解しやすいコード」を Coding Philosophy に掲げており(CONTRIBUTING.md)、ランタイムとしては異例なほど読みやすいコードベースです。この章は 1.7.5 のソースツリーの「地図」です。

## 7.1 リポジトリ構成

主要モジュールと中身(リポジトリ直下、および同梱の開発者ドキュメント AGENT.md の記述に基づく):

| ディレクトリ | 役割 | 入口となるクラス |
|---|---|---|
| `wasm/` | バイナリパーサ・バリデータ・型定義 | `Parser`、`Validator`、`types/*` |
| `runtime/` | インタプリタ・インスタンス・リンク | `Instance`、`InterpreterMachine`、`Store` |
| `compiler/` | Wasm → JVM バイトコードコンパイラ | `MachineFactoryCompiler`、`internal/Compiler` |
| `wasi/` | WASI Preview 1 実装 | `WasiPreview1`、`WasiOptions` |
| `simd/` | SIMD 命令(Vector API) | `SimdInterpreterMachine` |
| `annotations/` | @HostModule 等+プロセッサ | — |
| `build-time-compiler(-cli)/`、`compiler-maven-plugin/` | ビルド時コンパイル | `ChicoryCompilerGenMojo` |
| `wabt/`、`wasm-tools/` | wat2wasm 等のツール(自身が Chicory 上で動く) | `Wat2Wasm` |
| `runtime-tests/`、`compiler-tests/`、`machine-tests/`、`wasi-tests/` | spec テスト(自動生成) | — |
| `test-gen-plugin/`、`wasi-test-gen-plugin/` | .wast → JUnit 生成プラグイン | `TestGenMojo` |
| `fuzz/`、`jmh/`、`wasm-corpus/` | ファジング・ベンチ・テスト資材 | — |

依存グラフは 3.1 の図の通りで、`wasm → runtime → (wasi | compiler | simd | log)` という一方向の層構造です。

## 7.2 `wasm` モジュール — パーサとバリデータ

- **`Parser.java`** — Wasm バイナリフォーマットのセクション単位パーサ。`ParserListener` を渡すストリーミング風の口(`parse(InputStream, ParserListener)`)もあり、`WasmModule` を組み立てずにセクションを覗く用途に使えます。
- **`Validator.java`** — 型検査。Wasm 仕様書の appendix にある検証アルゴリズムに基づく実装であることが開発者ドキュメントに明記されています。読みどころは「バリデータが命令オペランドに型ヒントを埋め込む」設計で、`ref.test` / `ref.cast` / `br_on_cast`(WasmGC 系命令)の**ソースヒープ型を検証時に解決しておき、インタプリタが実行時に推測せずにディスパッチできる**ようにしています。「実行時に型計算を持ち込まない。検証時に前計算・キャッシュする」というプロジェクトの性能原則の実例です。
- **`types/`** — `ValType`、`FunctionType`、`OpCode` に加え、WasmGC 対応で入った `SubType` / `RecType` / `CompType` / `StructType` / `ArrayType` / `FieldType` / `StorageType` / `PackedType` など。GC プロポーザルの型システムがどう Java にモデル化されているかはここを読みます。

## 7.3 `runtime` モジュール — インタプリタの心臓部

- **`InterpreterMachine.java`** — 実行の主ループ。1.7.5 時点で約 3,500 行・**344 個の case ラベル**を持つオペコードディスパッチです。`MStack`(値スタック)、`StackFrame` / `CtrlFrame`(フレームと制御構造)と併せて読むと、Wasm がスタックマシンであることが素直に写像されているのが分かります。「まず InterpreterMachine の該当オペコードの case を読む」が Chicory デバッグの基本動作です。
- **`Instance.java`** — インスタンス化のシーケンス(インポート解決 → メモリ/テーブル/グローバル生成 → セグメント初期化 → start)。GC 参照の格納やヒープ型マッチングもここに集約されています。
- **`internal/GcRefStore.java`** — WasmGC の参照を自動採番キーで保持し、**マークスイープ回収**を行うストア。`WasmStruct`(フィールドをスタック値と同じ long エンコーディングで持つ)/ `WasmArray` / `WasmI31Ref` が GC オブジェクトの実行時表現です。「JVM の GC の上に Wasm の GC 意味論をどう載せるか」という設計問題への解がここで読めます。
- **`ConstantEvaluators.java`** — グローバル初期化やエレメント/データセグメントで使われる定数式の評価器。拡張定数式プロポーザル対応の実体。
- **`ByteBufferMemory` / `ByteArrayMemory`** — 線形メモリの2実装。境界チェックとエンディアン(Wasm はリトルエンディアン)の扱いを見るならここ。

## 7.4 `compiler` モジュール — Wasm → JVM バイトコード

エントリポイントは `MachineFactoryCompiler`、変換本体は `internal/Compiler` で、ASM(ow2)を使って **Wasm の各関数を JVM メソッドに**変換します。6.4 の巨大関数問題(JVM の 64KiB メソッド上限)はこの1対1変換の直接の帰結で、フォールバック時は `internal/CompilerInterpreterMachine` がコンパイル済みコードとインタプリタの混成実行を担います。ビルド時コンパイラ(`build-time-compiler`)は同じ変換器を Maven/Gradle/CLI から呼び出し、`.class` として書き出す皮です。

## 7.5 `wasi` モジュール

`WasiPreview1.java` に各 WASI 関数のホスト関数実装がフラットに並んでいます。「この WASI 関数はサポートされているか、どういう制限があるか」の一次情報はこのファイルで、公式ドキュメントの対応表(4.5)もここから生成される運用です。ファイルシステムは NIO の `FileSystem` 抽象に対して書かれており、Jimfs がテスト対象(4.5)。

## 7.6 テスト戦略の内幕 — 仕様準拠をどう担保しているか

Chicory の信頼性の根拠はここにあります。

1. **Wasm 公式テストスイート**(`testsuite/` サブモジュールの `.wast` ファイル群)を、自作の Maven プラグイン **`test-gen-plugin` がビルド時に JUnit テストクラスへ自動生成**します。
2. 生成されたテストは `runtime-tests`(インタプリタ)、`compiler-tests`(コンパイラ)、`machine-tests`(両エンジン共通)、`wasi-tests`(WASI テストスイート)で実行されます。**同じ spec テストを両エンジンに課す**ことが「コンパイラはインタプリタのドロップイン代替」という保証の実体です。
3. 新しい spec テストの追加は `runtime-tests/pom.xml` の `<includedWasts>` にファイル名を足して再生成、という手順が開発者ドキュメントに定義されています(`mvn surefire:test -pl runtime-tests -Dtest=SpecV1GcStructTest` のように単体実行も可能)。
4. 加えて `fuzz/` モジュールでファジングも行われています。

## 7.7 設計変遷 — なぜ今の形か

READMEのロードマップがそのまま設計史になっています。

| 年 | マイルストーン(抜粋) | 設計上の意味 |
|---|---|---|
| 2023 | バイナリパーサ、素朴なインタプリタ、spec テスト自動生成の確立 | 「正しさの検証装置」を最初に作った |
| 2024 | 全 spec テスト green(正しさ)、validation 実装(安全)、**v1.0 API 策定**、**インタプリタ/コンパイラのエンジン分離**、AOT が spec 全通過、WASIp1 | `Machine` 抽象の導入。1.0.0 は2024年12月 |
| 2025 | SIMD、Tail Call、コンパイラの実験卒業、例外処理、スレッド、拡張定数式 | プロポーザル追従期 |
| 2026 | **GC、Multi-Memory 完了。残タスクは Performance** | 機能面の主要プロポーザルは完走 |

「まずインタプリタで正しさを固定し、その spec テストを錨にしてコンパイラを育てる」という順序が、このプロジェクトの品質戦略の核心です。

---

# 第8章 APIリファレンス(モジュール別)

網羅的な Javadoc は各アーティファクトの javadoc jar(Maven Central)を参照してください。ここでは 1.7.5 の「どのアーティファクトに何が入っているか」と最重要 API を整理します。バージョンは BOM(2.1)で揃えるのが前提です。

## 8.1 アーティファクト一覧

| groupId はすべて `com.dylibso.chicory` | 役割 | 安定度 |
|---|---|---|
| `runtime` | 実行の中核(Instance/Store/Memory/HostFunction)。`wasm` を推移的に含む | 安定 |
| `wasm` | パーサ・バリデータ・型。runtime 経由で入る | 安定 |
| `wasi` | WASI Preview 1 | 安定 |
| `compiler` | ランタイムコンパイラ(要 ASM) | 安定(2025年に実験卒業) |
| `chicory-compiler-maven-plugin` / Gradle プラグイン / `build-time-compiler(-cli)` | ビルド時コンパイル | 安定 |
| `annotations` + `annotations-processor` | @HostModule / @WasmExport / @WasmModuleInterface | 安定 |
| `simd` | SIMD インタプリタ(Java 21+) | 条件付き |
| `log` | ロギング抽象 | 安定 |
| `bom` | バージョン整合 | — |
| `wabt` / `wasm-tools` | wat2wasm 等の開発ツール | 安定 |
| `dircache-experimental` | コンパイルキャッシュ | **実験的** |
| `cli` | コマンドライン実行 | **実験的** |

**「experimental」の意味**は公式に定義されています: 設計に 100% の確信が持てないため、**SemVer に縛られず破壊的変更(artifactId・クラス・メソッドの改名等)を行う可能性がある**モジュール。ただし「動いているものが完全に消される可能性は低い」。実験的モジュールを本番依存に入れるときはマイナーアップデートでもリリースノートを読む運用にしてください。

## 8.2 最重要 API 早見表

```java
// --- パース(wasm) ---
WasmModule Parser.parse(File | Path | byte[] | InputStream)
Parser.builder().withValidation(boolean).build()

// --- インスタンス化(runtime) ---
Instance.builder(WasmModule)
    .withImportValues(ImportValues)
    .withMachineFactory(Function<Instance, Machine>)
    .withMemoryLimits(MemoryLimits) / .withMemoryFactory(...)
    .withInitialize(boolean) / .withStart(boolean)
    .withUnsafeExecutionListener(ExecutionListener)
    .build()

// --- 呼び出し ---
ExportFunction f = instance.export(String name);
long[] result = f.apply(long... args);      // throws ChicoryException
Memory mem = instance.memory();

// --- 値変換(wasm.types.Value) ---
Value.floatToLong / longToFloat / doubleToLong / longToDouble

// --- ホスト関数 ---
new HostFunction(String module, String name,
                 FunctionType.of(List<ValType> params, List<ValType> results),
                 (Instance, long...) -> long[] /* または null */)
ImportValues.builder().addFunction(HostFunction...).build()

// --- リンク ---
new Store().addFunction(...).instantiate(String name, WasmModule)

// --- WASI ---
WasiOptions.builder().inheritSystem()
    .withStdin/withStdout/withStderr(...)
    .withArguments(List<String>).withEnvironment(k, v)
    .withDirectory(String guest, Path host).build()
WasiPreview1.builder().withOptions(opts).build().toHostFunctions()

// --- エンジン ---
MachineFactoryCompiler::compile                       // ランタイムコンパイラ
MachineFactoryCompiler.builder(module)
    .withInterpreterFallback(WARN|SILENT|FAIL)
    .withInterpretedFunctions(Set<Integer>).compile()
SimdInterpreterMachine::new                           // SIMD(Java 21+)
GeneratedClass::create                                // ビルド時コンパイル生成物
```

`ValType` の主な定数: `I32, I64, F32, F64, V128, FuncRef, ExternRef, ExnRef`(+GC 関連の参照型)。

## 8.3 アノテーション API

| アノテーション | 対象 | 効果 |
|---|---|---|
| `@HostModule("name")` | クラス | ホスト関数群のコンテナ。`<Class>_ModuleFactory.toHostFunctions(this)` が生成される |
| `@WasmExport` | インスタンスメソッド | ホスト関数として公開。名前省略時は camelCase → snake_case 変換。**static 不可**(ホスト状態と対話する前提のため) |
| `@WasmModuleInterface("mod.wasm")` | クラス | モジュールから型付きの `ModuleExports` / `ModuleImports`(`toImportValues()` 付き)を生成。クラスパス上のパスか `file://` URI を指定 |

対応する型変換は `int↔i32`、`long↔i64`、`float↔f32`、`double↔f64` の4種のみ。プロセッサは `annotations-processor` を `annotationProcessorPaths` に登録して有効化します(4章参照)。なおビルド時コンパイラ利用時は Maven プラグインの `moduleInterface` パラメータで同等物が生成でき、その方が簡単です(公式の tip)。


---

# 第9章 トラブルシューティングとFAQ

## 9.1 例外・エラーメッセージ別索引

| 症状 / 例外 | フェーズ | 典型原因 | 対処 |
|---|---|---|---|
| `MalformedException` | パース | ファイル破損、`.wat` を誤って渡した、途中切れダウンロード | バイナリのマジック(`\0asm`)確認。wat は wat2wasm で変換(2.3) |
| `InvalidException` | 検証 | 型検査違反。手書き/生成の不正モジュール、未対応プロポーザル使用の可能性 | ゲストのツールチェーンを最新化。使用プロポーザルを確認(4.9) |
| `UnlinkableException` | インスタンス化 | インポート未解決・シグネチャ不一致 | import 一覧を確認し、ホスト関数/WASI を接続(2.4/4.4/4.5)。名前空間・関数名・型の3点照合 |
| `UninstantiableException` | インスタンス化 | start 関数内のトラップ等 | start の実行を `withStart(false)` で切り分け |
| `TrapException`(unreachable) | 実行 | ゲストの panic/abort(Rust の panic は unreachable になる) | ゲスト側で panic ハンドラ/ログを仕込む。入力値を確認 |
| `TrapException`(out of bounds memory access) | 実行 | ポインタ/長さの受け渡しミス、alloc忘れ、解放済み領域アクセス | 4.3 のプロトコル(alloc→write→call→dealloc)を再点検 |
| `TrapException`(ゼロ除算・integer overflow 等) | 実行 | 文字通り | ゲストのロジック/入力検証 |
| `WasmRuntimeException`(WASI 関数) | 実行 | 未サポート WASI 機能の呼び出し(ソケット等) | 4.5 の対応表を確認。ゲストからその機能の使用を除去 |
| `WasiExitException` | 実行 | ゲストが `exit(n)` した | 準正常系として捕捉し終了コードを処理(5.6) |
| `ChicoryInterruptedException` | 実行 | タイムアウト等での interrupt | 打ち切りとして扱い、インスタンスを破棄(6.5) |
| stderr に `Warning: using interpreted mode for WASM function index: N` | コンパイラ | JVM メソッドサイズ上限超えの巨大関数 | 6.4 参照(最適化ビルド / withInterpretedFunctions / SILENT) |
| ビルド時 `WASM function size exceeds the Java method size limits ...` | ビルド | 同上(ビルド時コンパイラは既定 FAIL) | interpreterFallback を WARN にして関数一覧を収集 → 明示指定 |
| native-image でランタイムコンパイラが失敗 | ビルド/実行 | リフレクション+動的クラスロード | ビルド時コンパイルへ移行(4.7) |
| SIMD モジュールが動かない/遅い | 実行 | Java 21 未満、またはコンパイラと併用しようとした | Java 21+ & `SimdInterpreterMachine`(インタプリタ限定)(4.9) |

## 9.2 よくある落とし穴とアンチパターン

- **f32/f64 を数値のまま `apply` に渡す** — 必ず `Value.floatToLong/doubleToLong` でビットパターン化(2.2)。レビューで最頻出です。
- **`Instance` をスレッド間で共有して同時に呼ぶ** — インスタンスは実行状態の塊。共有するなら `WasmModule`(6.5)。
- **`Store` をシングルトンにしてマルチスレッドから触る** — 公式が非スレッドセーフと明言(4.8)。
- **信頼できないゲストに `withMemoryLimits` なしで実行させる** — 2GiB まで成長し得ます(6.3)。
- **WASI に実ディレクトリを直接プリオープンする** — 公式テスト対象は Jimfs。仮想 FS 経由が安全側(4.5)。
- **トラップしたインスタンスの使い回し** — ゲスト内部状態は不定。作り直す(5.6)。
- **validation の安易な無効化** — 特に SIMD では誤結果の警告あり(4.1)。
- **experimental モジュールを普通の依存と同じ感覚でアップグレード** — SemVer 対象外(8.1)。

## 9.3 デバッグ手法

1. **エンジンをインタプリタに戻して再現確認** — `withMachineFactory` を外すだけ。これで直るならコンパイラ絡み(まず 6.4 の巨大関数警告と 9.1 を確認)、直らないならモジュール自体かホスト統合の問題。
2. **ロギングを上げる** — 既定は JDK Platform Logging。`java.util.logging.config.file` で Chicory のログレベルを調整(4.9)。
3. **モジュールを外から検査** — wabt の `wasm-objdump -x` で import/export/メモリ宣言を確認。Chicory 同梱の `wasm-tools` / `wabt` アーティファクトを使えば JVM 内からも可能。
4. **メモリの中身を覗く** — ホスト関数やテストから `instance.memory().readBytes(addr, len)` でダンプ。ポインタ受け渡しバグの特定に直結。
5. **命令トレース(最終手段)** — `withUnsafeExecutionListener((insn, stack) -> ...)` で全命令をログ。極端に遅くなるので短い再現ケース限定(6.5)。
6. **別ランタイムと突き合わせる** — 同じ `.wasm` を wasmtime CLI 等で実行し、挙動が一致するかで「ゲストの問題か、ホスト統合の問題か」を切り分ける(9.4)。

## 9.4 「Wasm 側か、Chicory 側か、自分の統合か」切り分けフロー

```
問題発生
 ├─ パース/検証で失敗?
 │    └─ Yes → wasm-objdump 等でも読めない? → ゲストのビルド産物が不良
 │              読める → 使用プロポーザルとChicoryの対応状況を確認
 ├─ インスタンス化で失敗?(Unlinkable/Uninstantiable)
 │    └─ import の供給漏れ/型不一致 → ホスト統合(自分)の問題が大半
 ├─ 実行時トラップ?
 │    ├─ wasmtime 等でも同じ入力でトラップ → ゲストのバグ
 │    └─ Chicory だけトラップ → エンジン切替(インタプリタ⇔コンパイラ)で再現比較
 │         └─ 差が出る → Chicory のバグの可能性。最小再現を作って Issue/Zulip へ
 └─ 結果の値が違う?
      └─ f32/f64 のビット変換ミス(最頻出)→ 2.2 を再確認
```

## 9.5 FAQ

**Q. wat ファイルを直接ロードできますか?**
A. Chicory 本体に WAT パーサはまだありません。`wabt` / `wasm-tools` アーティファクトの `Wat2Wasm.parse` で変換してください(2.3)。

**Q. コンポーネントモデル / WASI Preview 2 は?**
A. 1.7.5 のサポートは WASI **Preview 1** です。ロードマップと最新状況は公式リポジトリを確認してください。

**Q. Android で使えますか?**
A. インタプリタは API 28+ で公式にテストされています。ランタイムコンパイラは動的クラスロードの制約があるため、Android ではインタプリタかビルド時コンパイルを選びます。メモリ実装は既定の `ByteBufferMemory` のままに(4.9)。

**Q. どのくらい速い?**
A. 「最速を目指さない」が公式の非目標です(1.3)。判断は 6.2 の手順で自分のワークロードを測ってください。

---

# 第10章 バージョン管理と移行

## 10.1 バージョニングポリシー

- 1.0.0(2024年12月)以降、安定モジュールは SemVer に基づく後方互換を重視しています。CONTRIBUTING の Coding Philosophy にも「可能な限り後方互換」が明記されています。
- **例外は `-experimental` を冠するモジュール**(dircache-experimental、cli 等)。前述の通り SemVer の対象外で、改名・API 変更が起こり得ます(8.1)。
- リリースは GitHub Releases(1.7.5 時点で15リリース)とMaven Central。変更履歴はリリースノートで追います。

## 10.2 0.x 時代のコードからの移行

Web 上の古い記事(2023〜2024年前半)は 0.x API のままのものが多く、検索経由で混乱しやすいポイントです。代表的な差分を「旧 → 新」で対比します。

| 0.x 時代の書き方(古い記事に頻出) | 1.x の書き方 |
|---|---|
| `Module.builder(wasmBytes).build()` | `Parser.parse(bytes)` で `WasmModule` を得る |
| `Value.i32(2)` を引数に渡す / `Value[]` が返る | `apply(long...)` に生の long を渡し、`long[]` を受ける |
| `Module` クラス(runtime パッケージ) | `WasmModule`(wasm パッケージ)+ `Instance` の分離 |
| AOT は実験的機能 | `compiler` は安定。`MachineFactoryCompiler` 経由(4.6) |

移行の実務は「①まず `Parser.parse` / `Instance.builder` / `export().apply(long...)` の3点に書き換える、②`Value` ラッパーを剥がして `Value.xxxToLong` 変換に置換する、③コンパイラ利用箇所を `MachineFactoryCompiler` に更新する」の順で機械的に進められます。

## 10.3 プロポーザル対応マトリクス(1.7.5 時点)

| Wasm プロポーザル | 対応 | 条件・備考 |
|---|---|---|
| コア仕様(MVP + validation) | ✅ | spec テストを CI で常時実行 |
| WASI Preview 1 | ✅ | ソケット系など一部関数は未対応(4.5) |
| SIMD | ✅ | Java 21+、インタプリタのみ(4.9) |
| Tail Call | ✅ | インタプリタ(およびコンパイラ) |
| Exception Handling | ✅ | `ExnRef` 型あり |
| Threads | ✅ | shared memory 対応 |
| Extended Constant Expressions | ✅ | |
| WasmGC | ✅ | 2026年対応。`WasmStruct`/`WasmArray`/`WasmI31Ref`(7.3) |
| Multi-Memory | ✅ | 2026年対応 |
| コンポーネントモデル / WASI P2 | — | 1.7.5 時点では対象外 |

## 10.4 ロードマップ

README のロードマップでは、2026年の残項目は **Performance** のみです(機能面の主要プロポーザルは完走)。今後のリリースでは実行性能・メモリ効率の改善が中心になると予想されるため、6.2 のベンチマークをバージョン更新時に回し直す運用と相性が良い局面です。

---

# 第11章 コントリビューションとコミュニティ

## 11.1 開発環境のセットアップ

必要なのは **Java 11+ と Maven**(同梱の `./mvnw` で可)。よく使うコマンドは開発者ドキュメントにまとまっています。

```bash
git clone https://github.com/dylibso/chicory && cd chicory

./mvnw clean install          # フルビルド+全テスト
./mvnw -Dquickly              # テスト・チェック全部スキップの高速インストール
./mvnw install -DskipTests    # テストのみスキップ
./mvnw -Ddev <goals>          # 開発中は linter/enforcer を無効化
./mvnw spotless:apply         # コミット前に必須の自動フォーマット
```

モジュールは同一ビルド内の Maven プラグインに依存するため単独ではビルドできず、**`-pl` と `-am` の併用が基本**です。

```bash
./mvnw install -pl runtime -am -DskipTests   # runtime と依存元をビルド
./mvnw test -pl runtime                       # runtime のユニットテストだけ実行
```

spec テストの回し方(7.6 参照):

```bash
./mvnw install -pl runtime-tests -am          # .wast から JUnit を生成して実行
./mvnw surefire:test -pl compiler-tests       # コンパイラの spec テスト
./mvnw surefire:test -pl runtime-tests -Dtest=SpecV1GcStructTest  # 単体
```

コードスタイル: ワイルドカード import 禁止、`spotless:apply` 必須。「性能のための複雑さより、読める単純さ」という Coding Philosophy(CONTRIBUTING.md)がレビュー基準の根っこにあります。

## 11.2 Issue・PR の出し方

- 小さな貢献(バグ報告・ドキュメント・例)も歓迎、と CONTRIBUTING が明言しています。まず CONTRIBUTING.md を読む(Legal/CLA 相当の記載を含む)。
- バグ報告は 9.4 の切り分け(別ランタイムとの比較、エンジン間の比較、最小再現 `.wat`)を添えると一級品になります。spec 準拠の問題なら該当 `.wast` を `<includedWasts>` に足すテストが最高の再現材料です。
- 迷ったら Zulip で先に相談するのが推奨動線です。

## 11.3 情報源まとめ

| 種別 | 場所 |
|---|---|
| 公式サイト / ドキュメント | https://chicory.dev (docs / blog) |
| リポジトリ | https://github.com/dylibso/chicory |
| チャット | Zulip(README に招待リンク) |
| Office Hours | 毎週火曜 16:00–16:30 UTC(リンクは Zulip で告知) |
| 講演 | Wasm I/O 2024「Creating a Language-Native Wasm Runtime」、Devoxx BE 2024、FOSDEM 2025「Wazero vs Chicory」、QCon London 2025、Wasm I/O 2026「The State of Zero-Dependency Wasm」ほか(README の On the press 節) |
| 記事 | InfoQ(紹介記事、SQLite×Wasm 記事)、Java Advent 2023/2024/2025、The New Stack、Baeldung、Quarkus ブログ(CEL エンジン事例) |

---

## おわりに

Chicory は「Wasm ランタイム」という重厚なテーマを、**Java 11 で動く読めるコード + 仕様テストによる正しさの担保 + 安全優先の明確な設計思想**という三点で成立させているプロジェクトです。本書の内容は 1.7.5 のソースと公式ドキュメントに基づいていますが、特に性能領域(2026年の主題)とプロポーザル対応は動きが速いため、実装時には対象バージョンのリリースノートと公式ドキュメントを併読してください。

**本書の検証環境**: Chicory 1.7.5(タグ `1.7.5`、コミット `181f6175`、2026-03-24)/ 参照ドキュメント: リポジトリ同梱 `docs/`、`README.md`、`CONTRIBUTING.md`、`AGENT.md` および chicory.dev 公開ドキュメント。
