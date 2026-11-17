package com.lab.demo_batch;   

// ─── 자바 기본 기능 ───────────────────────────────────────────
import java.nio.charset.StandardCharsets;         
import java.nio.file.Files;                        
import java.nio.file.Path;                         
import java.time.LocalDateTime;                    
import java.time.format.DateTimeFormatter;         
import java.util.ArrayList;
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
 * 가짜 FTP 수신 배치 설정.
 * SFTP(가짜)의 원본 파일을 받아와서 NAS(가짜)에 적재하는 배치를 흉내 냄
 *   원본 파일: SFTP에 있는 파일 (배치가 받아올 대상)
 *   적재 파일: 배치가 받아와서 NAS에 저장한 파일
 * 파일 확인(있는지, 크기 비교)은 밖의 nas-check.sh가 따로 함
 *
 *   경우 | 상황          | 배치 상태  | 종료 코드 | nas-check 결과    | 기대 색
 *   ----+---------------+-----------+----------+------------------+------
 *    1  | 정상수신       | COMPLETED | 0        | 0 OK             | 초록
 *    2  | 배치실패       | FAILED    | 0        | 2 NAS_MISSING    | 빨강
 *    3  | 파일부재       | COMPLETED | 0        | 3 SOURCE_MISSING | 빨강
 *    4  | 빈파일         | COMPLETED | 0        | 1 EMPTY          | 주황
 *    5  | 강제종료       | (중단)     | 1        | 2 NAS_MISSING    | 빨강
 *    6  | 파일크기다름    | COMPLETED | 0        | 4 SIZE_MISMATCH  | 빨강
 *
 * 색 기준
 *   초록: 정상
 *   주황: 정상인지 문제인지 형태만으로 판단 불가 → 사람이 직접 확인 (원본과 비교 등)
 *   빨강: 확실한 실패 → 즉시 조치
 */
@Configuration   
public class DemoBatchConfig {

    private static final Logger log = LoggerFactory.getLogger(DemoBatchConfig.class);

    // CSV 파일의 헤더(첫 줄). 경우 1에서 사용
    private static final String CSV_HEADER = "id,worker,score";

    /**
     * 원본 파일이 있는 폴더 (가짜 SFTP).
     * 실행할 때 --demo.source-dir=경로 로 바꿀 수 있고,
     * 지정하지 않으면 ':' 뒤의 기본값(/data/sftp-sim/outbound)을 씀.
     */
    @Value("${demo.source-dir:/data/sftp-sim/outbound}")
    private String sourceDir;

    /**
     * 적재 파일을 저장할 폴더 (가짜 NAS).
     * 실행할 때 --demo.output-dir=경로 로 바꿀 수 있고,
     * 지정하지 않으면 ':' 뒤의 기본값(/data/nas-sim/inbound)을 씀.
     */
    @Value("${demo.output-dir:/data/nas-sim/inbound}")
    private String outputDir;

    /**
     * 결과를 고정할 경우 번호.
     *   0 (기본값, 지정 안 함) → 실행할 때마다 1~5 중 무작위로 고름 (Jenkins에서 주기 실행할 때)
     *   1~5                   → 무작위 대신 항상 그 번호의 경우로 실행 (시험할 때)
     * 예) java -jar demo-batch.jar --demo.scenario=2   → 항상 "배치 실패(종료코드 0)"
     */
    @Value("${demo.scenario:0}")
    private int fixedScenario;

    /**
     * 실행마다 붙일 구분 번호. 파일 이름에 들어감 (CSTR_A_<번호>.csv)
     * Jenkins에서 빌드 번호를 넣어 "이번 실행이 만든 파일"을 찾을 수 있게 할 예정.
     * nas-check.sh에도 같은 파일 이름을 넘겨야 같은 파일을 비교함.
     * 지정하지 않으면 현재 시각(예: 20261117153000)을 씀.
     */
    @Value("${demo.run-id:}")
    private String runId;

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
    public Step fileReceiveStep(JobRepository jobRepository) {
        return new StepBuilder("fileReceiveStep", jobRepository)
                .tasklet(fileReceiveTasklet())
                .build();
    }

    /**
     * Tasklet = Step이 실제로 하는 일.
     * 1) 이번 실행의 경우 번호를 정하고
     * 2) 원본(SFTP)과 대상(NAS) 파일 경로를 만든 뒤
     * 3) (실습용) 원본 파일을 준비하고
     * 4) 원본 파일을 받아와서 NAS에 적재함 (경우에 따라 실패하거나 덜 적재함)
     */
    @Bean
    public Tasklet fileReceiveTasklet() {
        return (contribution, chunkContext) -> {

            // 1) 경우 번호 정하기: 고정값이 있으면 그것, 없으면(0) 무작위
            int scenario = fixedScenario > 0 ? fixedScenario : pickScenario();

            // 2) 파일 이름에 붙일 구분 번호: run-id가 있으면 그것, 없으면 현재 시각
            String id = (runId == null || runId.isBlank())
                    ? LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                    : runId;
            String fileName = "CSTR_A_" + id + ".csv";

            Path src = Path.of(sourceDir).resolve(fileName);   // 원본 파일 경로 (SFTP)
            Path nas = Path.of(outputDir).resolve(fileName);   // 적재 파일 경로 (NAS)
            Files.createDirectories(src.getParent());          // 폴더가 없으면 만듦
            Files.createDirectories(nas.getParent());

            // 이번 실행이 어떤 경우인지 로그로 남김 (Console에서 번호와 이름을 바로 확인)
            log.info("[DEMO] scenario = {} ({}), file = {}",
                    scenario, scenarioName(scenario), fileName);

            // 3) (실습용) 원본 파일 준비 — 진짜 SFTP가 없어서 앱이 미리 만들어 둠
            switch (scenario) {
                case 3 -> log.info("[SOURCE] no source file (simulated)");             // 파일부재: 원본 없음
                case 4 -> Files.write(src, new byte[0]);                                // 빈파일: 원본이 0바이트
                default -> Files.write(src, makeCsvLines(), StandardCharsets.UTF_8);    // 나머지: 정상 원본
            }

            // 4) 원본 파일을 받아와서 NAS에 적재

            // 경우 3: 파일부재 (원본 파일 없음)
            // 받아올 파일이 없음 → 배치는 할 일이 없으니 "정상 종료"
            // 배치 입장에선 정상(COMPLETED, 종료 코드 0)이지만, 오늘 필요한 파일이 없다는 건 문제
            // → Jenkins만 보면 놓침 → nas-check가 원본·적재 파일 모두 없음(SOURCE_MISSING)으로 잡을 대상
            if (!Files.exists(src)) {
                log.info("[DEMO] no file on remote server");
                log.info("[DEMO] READ = 0, WRITE = 0, SKIP = 0");   // SAFE-T 요약 줄 흉내
                return RepeatStatus.FINISHED;
            }

            switch (scenario) {

                case 2 -> {
                    // 경우 2: 배치실패
                    // 예외를 던지면 Spring Batch가 이 Step과 Job을 FAILED로 기록함.
                    // 하지만 메인 클래스가 종료 코드를 따로 넘기지 않아서 프로그램은 0으로 끝남.
                    // → Jenkins는 SUCCESS로 봄 → Text Finder로 실패 문구를 찾아 FAILURE로 바꿀 대상
                    // → 원본 파일은 있는데 적재 파일은 없음 → nas-check가 NAS_MISSING으로도 잡음
                    log.warn("[DEMO] remote server timeout (simulated)");
                    throw new IllegalStateException("remote server timeout (simulated)");
                }

                case 5 -> {
                    // 경우 5: 강제종료
                    // 프로그램을 종료 코드 1로 강제 종료 → Jenkins가 바로 FAILURE로 봄
                    // → 원본 파일은 있는데 적재 파일은 없음 → nas-check가 NAS_MISSING으로도 잡음
                    log.error("[DEMO] fatal error (simulated), exit 1");
                    System.exit(1);
                }

                case 6 -> {
                    // 경우 6: 파일크기다름
                    // 받아오는 도중 끊긴 것처럼 원본 파일의 앞 절반만 적재하고, 로그는 성공처럼 남김 (숨은 실패)
                    // → 배치·Jenkins 모두 성공으로 봄 → nas-check의 크기 비교(SIZE_MISMATCH)로만 잡힘
                    byte[] all = Files.readAllBytes(src);                     // 원본 파일 전체를 읽음
                    Files.write(nas, Arrays.copyOf(all, all.length / 2));    // 앞 절반만 적재
                    log.info("[DEMO] file saved: {} ({} bytes)", fileName, Files.size(nas));
                    log.info("[DEMO] READ = 1, WRITE = 1, SKIP = 0");
                }

                default -> {
                    // 경우 1(정상수신), 경우 4(빈파일): 원본 파일을 그대로 적재 (같은 이름이 있으면 덮어씀)
                    // 경우 4는 원본 파일도 0바이트라 적재 파일도 0바이트 → nas-check가 EMPTY(주황)로 판단
                    Files.copy(src, nas, StandardCopyOption.REPLACE_EXISTING);
                    log.info("[DEMO] file saved: {} ({} bytes)", fileName, Files.size(nas));
                    log.info("[DEMO] READ = 1, WRITE = 1, SKIP = 0");
                }
            }

            // "이 Step의 할 일은 끝났다"는 신호 (경우 1, 4, 6이 여기까지 옴)
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
     *   0~49  → 1 정상수신      (50%)
     *   50~59 → 2 배치실패      (10%)
     *   60~69 → 3 파일부재      (10%)
     *   70~79 → 4 빈파일        (10%)
     *   80~89 → 5 강제종료      (10%)
     *   90~99 → 6 파일크기다름   (10%)
     */
    private int pickScenario() {
        int r = ThreadLocalRandom.current().nextInt(100);
        if (r < 50) return 1;
        if (r < 60) return 2;
        if (r < 70) return 3;
        if (r < 80) return 4;
        if (r < 90) return 5;
        return 6;
    }

    /**
     * 경우 번호를 로그용 이름으로 바꿈.
     * Console 로그에서 번호 대신 이름을 보고 바로 알 수 있게 하기 위함.
     */
    private String scenarioName(int scenario) {
        return switch (scenario) {
            case 1 -> "NORMAL";              // 정상수신
            case 2 -> "BATCH_FAILED_EXIT0";  // 배치실패
            case 3 -> "NO_FILE";             // 파일부재
            case 4 -> "EMPTY_FILE_0B";       // 빈파일
            case 5 -> "CRASH_EXIT1";         // 강제종료
            case 6 -> "SIZE_MISMATCH";       // 파일크기다름
            default -> "UNKNOWN";
        };
    }
}