package com.lab.demo_batch;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 체크섬 도우미.
 *
 * 하는 일 2가지
 *   1) copyWithSha256 : 파일을 받으면서(쓰면서) 동시에 SHA-256을 계산
 *   2) writeSidecar   : 데이터 파일을 다 쓴 "뒤에" 같은 폴더에 <파일이름>.sha256 을 만듦
 *
 * .sha256 파일 내용 (sha256sum 명령과 같은 형식 → sha256sum -c 로 바로 검사 가능)
 *   <해시 64글자><공백 2칸><파일 이름>
 *   예) e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855  CSTR_A_t4.csv
 *
 * 순서가 중요한 이유
 *   데이터 파일 → .sha256 순서로 만들기 때문에
 *   ".sha256이 있다 = 데이터 파일 쓰기가 끝났다"로 볼 수 있음.
 *   데이터 파일만 있고 .sha256이 없으면 = 쓰다가 중간에 죽었을 수 있음 (확인 대상)
 */
public final class Sha256Sidecar {

    // .sha256 파일 이름 끝에 붙는 글자
    public static final String SUFFIX = ".sha256";

    private Sha256Sidecar() { }   // 도구 모음이라 객체를 만들 일 없음

    /**
     * 원본(src)을 대상(dst)으로 복사하면서 SHA-256을 계산해서 돌려줌.
     *
     * DigestOutputStream: 대상 파일에 쓰는 바이트를 MessageDigest에도 함께 넘김
     *   → 복사가 끝나면 "실제로 쓴 내용"의 해시가 나옴 (파일을 다시 읽을 필요 없음)
     *
     * 실제 FTP 배치에서는 src 대신 원격 서버에서 읽는 스트림이 들어오는 자리.
     *
     * @return 64글자 소문자 16진수 해시 (예: e3b0c442...)
     */
    public static String copyWithSha256(Path src, Path dst) throws IOException {
        MessageDigest md = newSha256();
        try (InputStream in = Files.newInputStream(src);
             OutputStream out = new DigestOutputStream(Files.newOutputStream(dst), md)) {
            in.transferTo(out);   // 원본을 끝까지 읽어서 대상에 씀 (쓰는 동안 해시도 같이 계산됨)
        }                         // try 블록을 나가면서 두 파일 모두 닫힘 = 데이터 파일 쓰기 끝
        return HexFormat.of().formatHex(md.digest());
    }

    /**
     * 데이터 파일 옆에 <파일이름>.sha256 을 만듦.
     * 반드시 데이터 파일을 다 쓰고 닫은 "뒤에" 호출해야 함.
     *
     * 임시 파일(.sha256.tmp)에 먼저 쓰고 이름을 바꾸는 이유
     *   쓰는 도중에 누가 읽으면 반쯤 쓰인 .sha256을 볼 수 있음
     *   → 다 쓴 뒤 이름을 한 번에 바꾸면 "없거나, 완성본이거나" 둘 중 하나만 보임
     *
     * @return 만든 .sha256 파일 경로
     */
    public static Path writeSidecar(Path dataFile, String sha256Hex) throws IOException {
        String name = dataFile.getFileName().toString();
        Path sidecar = dataFile.resolveSibling(name + SUFFIX);
        Path tmp = dataFile.resolveSibling(name + SUFFIX + ".tmp");

        // 형식: 해시 + 공백 2칸 + 파일 이름(경로 없이) + 줄바꿈
        Files.writeString(tmp, sha256Hex + "  " + name + "\n", StandardCharsets.UTF_8);
        Files.move(tmp, sidecar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return sidecar;
    }

    // SHA-256 MessageDigest 생성 (SHA-256은 모든 자바 구현에 필수로 포함된 알고리즘)
    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}