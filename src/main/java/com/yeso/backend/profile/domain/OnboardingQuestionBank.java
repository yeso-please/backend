package com.yeso.backend.profile.domain;

import java.util.List;
import java.util.Map;

import static com.yeso.backend.profile.domain.OnboardingAxis.EI;
import static com.yeso.backend.profile.domain.OnboardingAxis.JP;
import static com.yeso.backend.profile.domain.OnboardingAxis.SN;
import static com.yeso.backend.profile.domain.OnboardingAxis.TF;

/**
 * 온보딩 문항·선택지·임베딩 템플릿 매핑의 버전별 서버 상수다(docs/api/profile.md 2장).
 * 문항·선택·템플릿 매핑을 바꾸면 기존 version 값을 고치지 말고 새 {@code questionVersion}을 만든다.
 */
public final class OnboardingQuestionBank {

    /** 현재 설문: AI Hub 스타일·동기·선호 지역 + 여행 MBTI 12문항. */
    public static final String QUESTION_VERSION = "aihub-traveler-v2";
    /** 호환 설문: MBTI 없이 AI Hub 항목만. 계속 받는다. */
    public static final String V1_QUESTION_VERSION = "aihub-traveler-v1";
    /** 회원 임베딩 템플릿. MBTI는 표시용이라 v1·v2 모두 같은 템플릿을 쓴다. */
    public static final int AIHUB_TEMPLATE_VERSION = 2;

    public static final Map<Integer, TravelStyleQuestion> TRAVEL_STYLE_QUESTIONS = Map.of(
            1, new TravelStyleQuestion(1, "자연", "도시", "OFFICIAL"),
            3, new TravelStyleQuestion(3, "새로운 지역", "익숙한 지역", "INFERRED"),
            5, new TravelStyleQuestion(5, "휴양과 휴식", "체험 활동", "INFERRED"),
            6, new TravelStyleQuestion(6, "잘 알려지지 않은 곳", "잘 알려진 명소", "INFERRED")
    );

    public static final Map<Integer, String> TRAVEL_MOTIVES = Map.of(
            1, "일상에서 벗어나기",
            2, "휴식과 재충전",
            3, "동반자와 추억 만들기",
            4, "나를 돌아보기",
            5, "SNS에 올릴 사진",
            6, "운동과 건강",
            7, "새로운 경험",
            8, "역사와 문화 탐방",
            9, "특별한 날 기념"
    );

    public static final int MAX_TRAVEL_MOTIVES = 3;
    public static final int MAX_LIKED_REGIONS = 3;

    public record TravelStyleQuestion(int number, String leftPole, String rightPole, String evidence) {}

    /** v2의 여행 MBTI 문항. 표시용 분류라 임베딩에 쓰지 않는다. */
    public static final List<OnboardingQuestion> MBTI_QUESTIONS = List.of(
            new OnboardingQuestion(1, JP, "여행을 떠날 때 계획은",
                    "내가 걷는 길이 곧 여행코스", 'P', "계획은 필수", 'J'),
            new OnboardingQuestion(2, JP, "여행 경비는",
                    "당장 국제거지만 안되면 되지!", 'P', "걸어다니는 계산기로 변신", 'J'),
            new OnboardingQuestion(3, JP, "여행을 다녀온 후",
                    "홈스윗홈.. 침대로 점프!", 'P', "캐리어를 열고 물건을 정리한다", 'J'),
            new OnboardingQuestion(4, JP, "여행지에서 식사할 때",
                    "유~명한 맛집을 작정하고 노리는 헌터", 'J', "처음 본 순간 사랑에 빠진 길거리 가게", 'P'),
            new OnboardingQuestion(5, SN, "여행지에서 길을 잃었을 때",
                    "왔던 길로 돌아가는 헨젤과 그레텔st.", 'S', "자꾸 걸어 나가면 길이 있겠지, 지구는 둥그니까", 'N'),
            new OnboardingQuestion(6, SN, "화려한 건축물을 보며 드는 생각은",
                    "\"어떤 방법으로 지었을까?\" 고민한다", 'S', "\"와 멋있다...\" 감탄한다", 'N'),
            new OnboardingQuestion(7, TF, "아침에 늦잠 잔 친구에게",
                    "\"여행이 역시 피곤하지.\"", 'F', "\"내일은 시간 지키자.\"", 'T'),
            new OnboardingQuestion(8, TF, "친구에게 차 사고가 났다고 전화 왔을 때 나의 대답은",
                    "\"괜찮아? ㅠㅠ 다친 데는 없어?\"", 'F', "\"보험 들었어?\"", 'T'),
            new OnboardingQuestion(9, TF, "친구가 쓸데없는 기념품을 살 때",
                    "\"그래 니가 행복하다면...\"", 'F', "\"그거 결국 쓰레기 된다\"", 'T'),
            new OnboardingQuestion(10, EI, "나는 여행지를 선택할 때 주로",
                    "사람이 많은 도시로", 'E', "나무가 많은 자연으로", 'I'),
            new OnboardingQuestion(11, EI, "숙소를 구할 때",
                    "저녁에 바비큐 파티를 여는 곳", 'E', "조용하고 아늑한 곳", 'I'),
            new OnboardingQuestion(12, EI, "여행지에 대한 감상을",
                    "말로 내뱉어야 직성이 풀린다", 'E', "내 마음 속에 저장, 마음에 담고 느낀다", 'I')
    );

    /** MBTI 최종 코드 조립 순서(EI+SN+TF+JP)와 동일하다. */
    public static final List<OnboardingAxis> AXIS_ORDER = List.of(EI, SN, TF, JP);

    /** 온보딩에서는 받지 않고 여행기 경험 태그 사전으로 쓴다(docs/api/trip.md 6장). */
    public static final List<String> EXPERIENCE_TAGS = List.of(
            "자연", "바다", "산", "산책", "골목", "역사", "시장", "로컬 음식", "카페", "휴식", "실내", "체험");

    public static final List<String> EXCLUDE_TAGS = List.of(
            "계단·경사 많은 곳", "물놀이", "야간 이동", "오래 걷기");

    private OnboardingQuestionBank() {
    }
}
