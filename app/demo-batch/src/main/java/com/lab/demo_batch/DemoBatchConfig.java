package com.lab.demo_batch;   

// ─── 자바 기본 기능 ───────────────────────────────────────────
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

// ─── 로그 기능 ────────────────────────────────────────────────
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// ─── Spring Batch 6 ──────────
import org.springframework.batch.core.job.Job;                       // 배치 작업(Job)
import org.springframework.batch.core.job.builder.JobBuilder;        // Job을 만드는 도구
import org.springframework.batch.core.repository.JobRepository;      // 실행 기록 저장소
import org.springframework.batch.core.step.Step;                     // Job 안의 단계(Step)
import org.springframework.batch.core.step.builder.StepBuilder;      // Step을 만드는 도구
import org.springframework.batch.core.step.tasklet.Tasklet;          // Step이 실제로 할 일
import org.springframework.batch.infrastructure.repeat.RepeatStatus; // "할 일 끝남" 신호

// ─── Spring 기본 기능 ─────────────────────────────────────────
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 가짜 FTP 수신 배치 설정 (SAFE-T의 ZTNA FTP 배치 흉내).
 * SFTP(가짜)의 원본 파일을 받아와서 NAS(가짜)의 날짜 폴더에 적재하고,
 * 받으면서 계산한 SHA-256을 <파일이름>.sha256 으로 같이 남김.
 *
 *   원본 파일: SFTP에 있는 파일          /data/sftp-sim/outbound/CSTR_A_<id>.csv
 *   적재 파일: NAS 날짜 폴더에 저장한 파일  /data/nas-sim/inbound/<yyyyMMdd>/CSTR_A_<id>.csv
 *   체크섬   : 적재 파일 옆                /data/nas-sim/inbound/<yyyyMMdd>/CSTR_A_<id>.csv.sha256
 *
 * 앱은 상황을 "흉내"만 냄. 정상/문제 판정과 색은 앱 밖(nas-check.sh, Grafana)에서 정함.
 * 메인 클래스가 종료 코드를 따로 넘기지 않음 (실제 SAFE-T와 같은 상태)
 *   → 배치가 FAILED/STOPPED여도 종료 코드 0 → Jenkins는 SUCCESS (숨은 실패)
 *
 *   경우 | 상황            | 배치 상태   | 종료 코드 | NAS에 남는 것                 | 흉내 낸 실제 상황
 *   ----+-----------------+-----------+----------+-----------------------------+------------------------
 *    1  | 정상수신          | COMPLETED | 0        | 데이터 + .sha256 (일치)       | 정상
 *    2  | 받을파일없음      | COMPLETED | 0        | 없음                         | 원격에 파일이 아직 없음
 *    3  | 오류후중단        | STOPPED   | 0        | 없음                         | 실제 로그 2 (ERROR 후 STOPPED)
 *    4  | 빈파일            | COMPLETED | 0        | 0바이트 데이터 + .sha256       | 원본 자체가 빈 파일
 *    5  | 체크섬다름        | COMPLETED | 0        | 데이터 + .sha256 (불일치)      | 적재 후 파일이 바뀜/덜 써짐
 *    6  | 체크섬없음        | (중단)     | 1        | 데이터만 (.sha256 없음)        | 데이터 쓰고 바로 죽음
 *    7  | 배치실패          | FAILED    | 0        | 없음                         | 예외 → FAILED인데 종료 코드 0
 *    8  | 시작실패          | (시작 못함) | 1        | 없음                         | 실제 로그 1 (설정 오류로 시작 실패)
 */
@Configuration
public class DemoBatchConfig {

    private static final Logger log = LoggerFactory.getLogger(DemoBatchConfig.class);

    // CSV 파일의 헤더(첫 줄)
    private static final String CSV_HEADER = "id,worker,score";

    // 날짜 폴더 이름 형식 (예: 20261118)
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * 원본 파일이 있는 폴더 (가짜 SFTP).
     * 실행할 때 --demo.source-dir=경로 로 바꿀 수 있고,
     * 지정하지 않으면 ':' 뒤의 기본값(/data/sftp-sim/outbound)을 씀.
     */
    @Value("${demo.source-dir:/data/sftp-sim/outbound}")
    private String sourceDir;

    /**
     * 적재 파일을 저장할 폴더 (가짜 NAS). 실제 저장은 이 아래 날짜 폴더에 함.
     * 실행할 때 --demo.output-dir=경로 로 바꿀 수 있고,
     * 지정하지 않으면 ':' 뒤의 기본값(/data/nas-sim/inbound)을 씀.
     */
    @Value("${demo.output-dir:/data/nas-sim/inbound}")
    private String outputDir;

    /**
     * 결과를 고정할 경우 번호.
     *   0 (기본값, 지정 안 함) → 실행할 때마다 1~8 중 무작위로 고름 (Jenkins에서 주기 실행할 때)
     *   1~8                   → 무작위 대신 항상 그 번호의 경우로 실행 (시험할 때)
     * 예) java -jar demo-batch.jar --demo.scenario=3   → 항상 "오류후중단(STOPPED, 종료 코드 0)"
     */
    @Value("${demo.scenario:0}")
    private int fixedScenario;

    /**
     * 실행마다 붙일 구분 번호. 파일 이름에 들어감 (CSTR_A_<번호>.csv)
     * Jenkins에서 빌드 번호를 넣어 "이번 실행이 만든 파일"을 찾을 수 있게 함.
     * nas-check.sh에도 같은 파일 이름을 넘겨야 같은 파일을 검사함.
     * 지정하지 않으면 현재 시각(예: 20261118153000)을 씀.
     */
    @Value("${demo.run-id:}")
    private String runId;

    /**
     * NAS 날짜 폴더 이름 (yyyyMMdd).
     * Jenkins에서 nas-check.sh와 같은 날짜를 쓰도록 넘겨줄 예정 (자정 전후로 날짜가 갈리는 것 방지).
     * 지정하지 않으면 오늘 날짜. 주의: "오늘"은 이 앱이 도는 컴퓨터의 시간대 기준
     *   (내 PC는 한국 시간, Jenkins 에이전트 Pod는 UTC일 수 있음)
     */
    @Value("${demo.date:}")
    private String date;

    /**
     * 이번 실행의 경우 번호를 담는 작은 상자.
     * Integer를 그대로 Bean으로 쓰면 다른 Integer와 헷갈릴 수 있어서 전용 타입을 만듦.
     */
    public record DemoScenario(int number) { }

    /**
     * 이번 실행의 경우 번호를 "앱이 시작될 때" 정함.
     * 경우 8(시작실패)은 배치가 돌기 전에 앱 자체가 못 뜨는 상황이라
     * Step 안이 아니라 여기(앱 준비 단계)에서 예외를 던져야 흉내가 됨.
     */
    @Bean
    public DemoScenario demoScenario() {
        int scenario = fixedScenario > 0 ? fixedScenario : pickScenario();
        log.info("[DEMO] scenario = {} ({})", scenario, scenarioName(scenario));

        if (scenario == 8) {
            // 경우 8: 시작실패
            // 앱 준비 중 예외 → Spring Boot가 "Application run failed"를 찍고 종료 코드 1로 끝남
            // → Job이 아예 실행되지 않으므로 "status: [...]" 줄도 없음 → Jenkins는 FAILURE
            // 실제 로그 1("No job found with name ...")처럼 설정 오류로 못 뜨는 상황
            throw new IllegalStateException("No job found with name 'DEMO_WRONG_NAME' (simulated)");
        }
        return new DemoScenario(scenario);
    }

    /**
     * Job(배치 작업) 정의.
     * 이름이 "demoBatchJob"이고, fileReceiveStep 하나를 실행함.
     * Spring Boot는 앱이 시작되면 이 Job을 자동으로 실행함.
     */
    @Bean
    public Job demoBatchJob(JobRepository jobRepository, Step fileReceiveStep) {
        return new JobBuilder("demoBatchJob", jobRepository)
                .start(fileReceiveStep)   // 첫 번째(이자 유일한) 단계
                .build();
    }

    /**
     * Step(단계) 정의.
     * 이 단계가 실제로 할 일은 아래 fileReceiveTasklet()에 들어 있음.
     */
    @Bean
    public Step fileReceiveStep(JobRepository jobRepository, Tasklet fileReceiveTasklet) {
        return new StepBuilder("fileReceiveStep", jobRepository)
                .tasklet(fileReceiveTasklet)
                .build();
    }

    /**
     * Tasklet = Step이 실제로 하는 일.
     * 1) 파일 이름과 경로(원본, NAS 날짜 폴더)를 정하고
     * 2) (실습용) 원본 파일을 준비한 뒤
     * 3) 원본 파일을 받아오면서 SHA-256을 계산해 NAS에 적재하고
     * 4) 데이터 파일을 다 쓴 뒤 .sha256 을 남김 (경우에 따라 중간에 실패하거나 중단함)
     */
    @Bean
    public Tasklet fileReceiveTasklet(DemoScenario demoScenario) {
        return (contribution, chunkContext) -> {

            int scenario = demoScenario.number();

            // 1) 파일 이름과 경로
            String id = (runId == null || runId.isBlank())
                    ? LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                    : runId;
            String day = (date == null || date.isBlank()) ? LocalDate.now().format(DAY) : date;
            String fileName = "CSTR_A_" + id + ".csv";

            Path srcDir = Path.of(sourceDir);                   // 원본 폴더 (SFTP)
            Path nasDir = Path.of(outputDir).resolve(day);      // 적재 폴더 (NAS 날짜 폴더)
            Path src = srcDir.resolve(fileName);                // 원본 파일
            Path nas = nasDir.resolve(fileName);                // 적재 파일
            Files.createDirectories(srcDir);                    // 폴더가 없으면 만듦
            Files.createDirectories(nasDir);

            log.info("[DEMO] file = {}, nas dir = {}", fileName, nasDir);

            // 2) (실습용) 원본 파일 준비 — 진짜 SFTP가 없어서 앱이 미리 만들어 둠
            switch (scenario) {
                case 2, 3 -> log.info("[SOURCE] no source file (simulated)");          // 원본 없음
                case 4 -> Files.write(src, new byte[0]);                                // 원본이 0바이트
                default -> Files.write(src, makeCsvLines(), StandardCharsets.UTF_8);    // 정상 원본
            }

            // 3) 받아오기 전에 끝나는 경우들
            switch (scenario) {

                case 2 -> {
                    // 경우 2: 받을파일없음
                    // 원격에 받을 파일이 없음 → 배치는 할 일이 없으니 정상 종료 (COMPLETED, 종료 코드 0)
                    // 실제 FTP 배치도 목록이 비면 그냥 COMPLETED로 끝남
                    // 문제인지 아닌지는 "그 시간에 파일이 와야 했는지"를 알아야 판단 가능 → 지금은 판정 보류
                    log.info("[DEMO] no file on remote server");
                    log.info("[DEMO] READ = 0, WRITE = 0, SKIP = 0");
                    return RepeatStatus.FINISHED;
                }

                case 3 -> {
                    // 경우 3: 오류후중단 (실제 로그 2 흉내)
                    // ERROR 로그를 남기고 Step에 "여기서 멈춰라" 표시(setTerminateOnly)를 함
                    // → Spring Batch가 Step과 Job을 STOPPED로 기록, 종료 코드는 0
                    // → Jenkins는 SUCCESS (숨은 실패). 로그의 "status: [STOPPED]" 줄로만 알 수 있음
                    log.error("[DEMO] file Error: {}", srcDir);
                    log.info("[DEMO] READ = 0, WRITE = 0, SKIP = 0");
                    contribution.getStepExecution().setTerminateOnly();
                    return RepeatStatus.FINISHED;
                }

                case 7 -> {
                    // 경우 7: 배치실패
                    // 예외를 던지면 Spring Batch가 Step과 Job을 FAILED로 기록함
                    // 하지만 메인 클래스가 종료 코드를 따로 넘기지 않아서 프로그램은 0으로 끝남
                    // → Jenkins는 SUCCESS (숨은 실패). 로그의 "status: [FAILED]" 줄로 알 수 있음
                    // → 원본은 있는데 NAS에는 아무것도 없음
                    log.warn("[DEMO] remote server timeout (simulated)");
                    throw new IllegalStateException("remote server timeout (simulated)");
                }

                default -> { }   // 나머지는 아래 4)로 진행
            }

            // 4) 원본 파일을 받아오면서 SHA-256 계산 → NAS에 적재
            String sha256 = Sha256Sidecar.copyWithSha256(src, nas);
            log.info("[DEMO] file saved: {} ({} bytes)", fileName, Files.size(nas));

            if (scenario == 6) {
                // 경우 6: 체크섬없음
                // 데이터 파일은 다 썼는데 .sha256을 쓰기 전에 프로그램이 죽음 (종료 코드 1)
                // → Jenkins는 FAILURE. NAS에는 데이터 파일만 남음
                // → nas-check가 "체크섬 파일 없음"으로 잡을 대상
                log.error("[DEMO] fatal error before writing checksum (simulated), exit 1");
                System.exit(1);
            }

            // 데이터 파일을 다 쓰고 닫은 "뒤에" .sha256 작성
            Path sidecar = Sha256Sidecar.writeSidecar(nas, sha256);
            log.info("[DEMO] checksum saved: {} (sha256 = {})", sidecar.getFileName(), sha256);

            if (scenario == 5) {
                // 경우 5: 체크섬다름
                // .sha256까지 정상으로 쓴 뒤, 적재 파일이 바뀐 상황 (앞 절반만 남김)
                // → 배치·Jenkins 모두 성공으로 봄 → nas-check의 체크섬 비교로만 잡힘
                byte[] all = Files.readAllBytes(nas);
                Files.write(nas, Arrays.copyOf(all, all.length / 2));
                log.info("[DEMO] nas file changed after write (simulated): {} bytes", Files.size(nas));
            }

            // 경우 1(정상수신), 4(빈파일), 5(체크섬다름)가 여기까지 옴
            // 경우 4는 원본이 0바이트라 적재 파일도 0바이트 → .sha256은 "빈 파일의 해시"로 일치함
            // → 체크섬만으로는 정상과 구분 안 됨 → nas-check가 크기 0을 따로 확인할 대상
            log.info("[DEMO] READ = 1, WRITE = 1, SKIP = 0");
            return RepeatStatus.FINISHED;
        };
    }

    /**
     * 정상 원본 파일 내용을 만듦: 헤더 + 10~100건의 무작위 데이터
     */
    private List<String> makeCsvLines() {
        int n = ThreadLocalRandom.current().nextInt(10, 101);
        List<String> lines = new ArrayList<>();
        lines.add(CSV_HEADER);
        for (int i = 1; i <= n; i++) {
            lines.add(i + ",worker" + ThreadLocalRandom.current().nextInt(1000)
                    + "," + ThreadLocalRandom.current().nextInt(101));
        }
        return lines;
    }

    /**
     * 무작위로 경우 번호를 고름 (fixedScenario가 0일 때만 사용).
     * 0~99 사이 숫자를 뽑아서 구간별로 나눔:
     *   0~43  → 1 정상수신     (44%)
     *   44~51 → 2 받을파일없음  (8%)
     *   52~59 → 3 오류후중단    (8%)
     *   60~67 → 4 빈파일       (8%)
     *   68~75 → 5 체크섬다름    (8%)
     *   76~83 → 6 체크섬없음    (8%)
     *   84~91 → 7 배치실패      (8%)
     *   92~99 → 8 시작실패      (8%)
     */
    private int pickScenario() {
        int r = ThreadLocalRandom.current().nextInt(100);
        if (r < 44) return 1;
        return 2 + (r - 44) / 8;   // 44부터 8칸씩 → 2, 3, 4, 5, 6, 7, 8
    }

    /**
     * 경우 번호를 로그용 이름으로 바꿈.
     * Console 로그에서 번호 대신 이름을 보고 바로 알 수 있게 하기 위함.
     */
    private String scenarioName(int scenario) {
        return switch (scenario) {
            case 1 -> "NORMAL";             // 정상수신
            case 2 -> "NO_FILE";            // 받을파일없음
            case 3 -> "ERROR_STOPPED";      // 오류후중단
            case 4 -> "EMPTY_FILE_0B";      // 빈파일
            case 5 -> "CHECKSUM_MISMATCH";  // 체크섬다름
            case 6 -> "CHECKSUM_MISSING";   // 체크섬없음
            case 7 -> "BATCH_FAILED_EXIT0"; // 배치실패
            case 8 -> "STARTUP_FAILED";     // 시작실패
            default -> "UNKNOWN";
        };
    }
}