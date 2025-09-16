package cs.quizzapp.prokect.backend.services;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.http.ResponseEntity;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class OpenTDBService {

    private final RestTemplate restTemplate = new RestTemplate();
    private static final String BASE_URL = "https://opentdb.com/api.php";
    private static final String CATEGORY_URL = "https://opentdb.com/api_category.php";
    private static final String TOKEN_URL = "https://opentdb.com/api_token.php?command=request";

    private String sessionToken = null;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CategoryResponse {
        @JsonProperty("trivia_categories")
        private List<Category> triviaCategories;

        public List<Category> getTriviaCategories() { return triviaCategories; }
        public void setTriviaCategories(List<Category> triviaCategories) { this.triviaCategories = triviaCategories; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Category {
        private int id;
        private String name;

        public int getId() { return id; }
        public void setId(int id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class QuestionResponse {
        @JsonProperty("response_code")
        private int responseCode;
        private List<Question> results;

        public int getResponseCode() { return responseCode; }
        public void setResponseCode(int responseCode) { this.responseCode = responseCode; }
        public List<Question> getResults() { return results; }
        public void setResults(List<Question> results) { this.results = results; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Question {
        private String category;
        private String type;
        private String difficulty;
        private String question;
        @JsonProperty("correct_answer")
        private String correctAnswer;
        @JsonProperty("incorrect_answers")
        private List<String> incorrectAnswers;

        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getDifficulty() { return difficulty; }
        public void setDifficulty(String difficulty) { this.difficulty = difficulty; }
        public String getQuestion() { return question; }
        public void setQuestion(String question) { this.question = question; }
        public String getCorrectAnswer() { return correctAnswer; }
        public void setCorrectAnswer(String correctAnswer) { this.correctAnswer = correctAnswer; }
        public List<String> getIncorrectAnswers() { return incorrectAnswers; }
        public void setIncorrectAnswers(List<String> incorrectAnswers) { this.incorrectAnswers = incorrectAnswers; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TokenResponse {
        @JsonProperty("response_code")
        private int responseCode;
        @JsonProperty("response_message")
        private String responseMessage;
        private String token;

        public int getResponseCode() { return responseCode; }
        public void setResponseCode(int responseCode) { this.responseCode = responseCode; }
        public String getResponseMessage() { return responseMessage; }
        public void setResponseMessage(String responseMessage) { this.responseMessage = responseMessage; }
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
    }

    public List<Category> fetchCategories() {
        try {
            ResponseEntity<CategoryResponse> response = restTemplate.getForEntity(CATEGORY_URL, CategoryResponse.class);

            if (response.getBody() != null && response.getBody().getTriviaCategories() != null) {
                return response.getBody().getTriviaCategories();
            }

            return getDefaultCategories();

        } catch (ResourceAccessException e) {
            System.err.println("Failed to fetch categories from OpenTDB: " + e.getMessage());
            return getDefaultCategories();
        } catch (Exception e) {
            System.err.println("Unexpected error fetching categories: " + e.getMessage());
            return getDefaultCategories();
        }
    }

    private String getSessionToken() {
        if (sessionToken == null) {
            try {
                ResponseEntity<TokenResponse> response = restTemplate.getForEntity(TOKEN_URL, TokenResponse.class);

                if (response.getBody() != null && response.getBody().getResponseCode() == 0) {
                    sessionToken = response.getBody().getToken();
                }
            } catch (Exception e) {
                System.err.println("Failed to get session token: " + e.getMessage());
            }
        }
        return sessionToken;
    }

    private void resetSessionToken() {
        if (sessionToken != null) {
            try {
                String resetUrl = "https://opentdb.com/api_token.php?command=reset&token=" + sessionToken;
                restTemplate.getForEntity(resetUrl, String.class);
            } catch (Exception e) {
                System.err.println("Failed to reset session token: " + e.getMessage());
            }
        }
        sessionToken = null;
    }

    public List<Question> fetchQuestionsByCategory(String categoryName, int amount, String difficulty) {
        List<Category> categories = fetchCategories();
        int categoryId = -1;

        for (Category category : categories) {
            if (category.getName().equalsIgnoreCase(categoryName) ||
                    category.getName().toLowerCase().contains(categoryName.toLowerCase())) {
                categoryId = category.getId();
                break;
            }
        }

        StringBuilder urlBuilder = new StringBuilder(BASE_URL);
        urlBuilder.append("?amount=").append(Math.min(amount, 50));

        if (categoryId != -1) {
            urlBuilder.append("&category=").append(categoryId);
        }

        if (difficulty != null && !difficulty.isEmpty() && !difficulty.equalsIgnoreCase("any")) {
            urlBuilder.append("&difficulty=").append(difficulty.toLowerCase());
        }

        String token = getSessionToken();
        if (token != null) {
            urlBuilder.append("&token=").append(token);
        }

        urlBuilder.append("&encode=url3986");

        return fetchQuestionsWithRetry(urlBuilder.toString(), 3);
    }

    private List<Question> fetchQuestionsWithRetry(String url, int maxRetries) {
        int attempts = 0;

        while (attempts < maxRetries) {
            try {
                ResponseEntity<QuestionResponse> response = restTemplate.getForEntity(url, QuestionResponse.class);

                if (response.getBody() != null) {
                    QuestionResponse questionResponse = response.getBody();

                    switch (questionResponse.getResponseCode()) {
                        case 0:
                            List<Question> questions = questionResponse.getResults();
                            if (questions != null && !questions.isEmpty()) {
                                return decodeQuestions(questions);
                            }
                            break;

                        case 1:
                            System.err.println("Not enough questions available for this query");
                            return new ArrayList<>();

                        case 2:
                            System.err.println("Invalid parameters in OpenTDB request");
                            return new ArrayList<>();

                        case 3:
                            sessionToken = null;
                            if (attempts < maxRetries - 1) {
                                url = removeTokenFromUrl(url);
                                attempts++;
                                continue;
                            }
                            break;

                        case 4:
                            resetSessionToken();
                            if (attempts < maxRetries - 1) {
                                String newToken = getSessionToken();
                                url = updateTokenInUrl(url, newToken);
                                attempts++;
                                continue;
                            }
                            break;

                        case 5:
                            try {
                                Thread.sleep(6000);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            break;
                    }
                }

            } catch (ResourceAccessException e) {
                System.err.println("Network error fetching questions (attempt " + (attempts + 1) + "): " + e.getMessage());
            } catch (Exception e) {
                System.err.println("Unexpected error fetching questions (attempt " + (attempts + 1) + "): " + e.getMessage());
            }

            attempts++;

            if (attempts < maxRetries) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        System.err.println("Failed to fetch questions after " + maxRetries + " attempts");
        return new ArrayList<>();
    }

    private List<Question> decodeQuestions(List<Question> questions) {
        return questions.stream().map(this::decodeQuestion).collect(Collectors.toList());
    }

    private Question decodeQuestion(Question question) {
        try {
            question.setQuestion(java.net.URLDecoder.decode(question.getQuestion(), "UTF-8"));
            question.setCorrectAnswer(java.net.URLDecoder.decode(question.getCorrectAnswer(), "UTF-8"));

            if (question.getIncorrectAnswers() != null) {
                List<String> decodedIncorrect = question.getIncorrectAnswers().stream()
                        .map(answer -> {
                            try {
                                return java.net.URLDecoder.decode(answer, "UTF-8");
                            } catch (Exception e) {
                                return answer;
                            }
                        })
                        .collect(Collectors.toList());
                question.setIncorrectAnswers(decodedIncorrect);
            }
        } catch (Exception e) {
            System.err.println("Error decoding question: " + e.getMessage());
        }

        return question;
    }

    private String removeTokenFromUrl(String url) {
        return url.replaceAll("&token=[^&]*", "");
    }

    private String updateTokenInUrl(String url, String newToken) {
        if (newToken == null) {
            return removeTokenFromUrl(url);
        }

        if (url.contains("&token=")) {
            return url.replaceAll("&token=[^&]*", "&token=" + newToken);
        } else {
            return url + "&token=" + newToken;
        }
    }

    private List<Category> getDefaultCategories() {
        List<Category> defaultCategories = new ArrayList<>();

        String[] defaultCats = {
                "9:General Knowledge",
                "10:Entertainment: Books",
                "11:Entertainment: Film",
                "12:Entertainment: Music",
                "13:Entertainment: Musicals & Theatres",
                "14:Entertainment: Television",
                "15:Entertainment: Video Games",
                "16:Entertainment: Board Games",
                "17:Science & Nature",
                "18:Science: Computers",
                "19:Science: Mathematics",
                "20:Mythology",
                "21:Sports",
                "22:Geography",
                "23:History",
                "24:Politics",
                "25:Art",
                "26:Celebrities",
                "27:Animals",
                "28:Vehicles",
                "29:Entertainment: Comics",
                "30:Science: Gadgets",
                "31:Entertainment: Japanese Anime & Manga",
                "32:Entertainment: Cartoon & Animations"
        };

        for (String catStr : defaultCats) {
            String[] parts = catStr.split(":", 2);
            Category category = new Category();
            category.setId(Integer.parseInt(parts[0]));
            category.setName(parts[1]);
            defaultCategories.add(category);
        }

        return defaultCategories;
    }

    public String getCategoryNameById(int categoryId) {
        List<Category> categories = fetchCategories();
        return categories.stream()
                .filter(cat -> cat.getId() == categoryId)
                .map(Category::getName)
                .findFirst()
                .orElse("General Knowledge");
    }

    public List<String> getAvailableDifficulties() {
        return Arrays.asList("any", "easy", "medium", "hard");
    }

    public List<String> getAvailableTypes() {
        return Arrays.asList("any", "multiple", "boolean");
    }

    public List<Question> fetchQuestions(Integer categoryId, String difficulty, String type, int amount) {
        StringBuilder urlBuilder = new StringBuilder(BASE_URL);
        urlBuilder.append("?amount=").append(Math.min(amount, 50));

        if (categoryId != null && categoryId > 0) {
            urlBuilder.append("&category=").append(categoryId);
        }

        if (difficulty != null && !difficulty.isEmpty() && !difficulty.equalsIgnoreCase("any")) {
            urlBuilder.append("&difficulty=").append(difficulty.toLowerCase());
        }

        if (type != null && !type.isEmpty() && !type.equalsIgnoreCase("any")) {
            urlBuilder.append("&type=").append(type);
        }

        String token = getSessionToken();
        if (token != null) {
            urlBuilder.append("&token=").append(token);
        }

        urlBuilder.append("&encode=url3986");

        return fetchQuestionsWithRetry(urlBuilder.toString(), 3);
    }
}