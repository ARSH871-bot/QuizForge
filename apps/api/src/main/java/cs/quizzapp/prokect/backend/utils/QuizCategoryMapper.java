package cs.quizzapp.prokect.backend.utils;

import java.util.HashMap;
import java.util.Map;

public class QuizCategoryMapper {

    private static final Map<String, Integer> CATEGORY_MAP = new HashMap<>();
    private static int nextCategoryId = 26; // Start with a number greater than the existing categories
    static {
        CATEGORY_MAP.put("General Knowledge", 9);
        CATEGORY_MAP.put("Books", 10);
        CATEGORY_MAP.put("Film", 11);
        CATEGORY_MAP.put("Music", 12);
        CATEGORY_MAP.put("Science & Nature", 17);
        CATEGORY_MAP.put("Sports", 21);
        CATEGORY_MAP.put("Geography", 22);
        CATEGORY_MAP.put("History", 23);
        CATEGORY_MAP.put("Mathematics", 19);
        CATEGORY_MAP.put("Art", 25);
    }

    public static Integer getCategoryId(String categoryName) {
        return CATEGORY_MAP.getOrDefault(categoryName, null);
    }

    public static Map<String, Integer> getAllCategories() {
        return CATEGORY_MAP;
    }

    public static boolean addCategory(String categoryName) {
        if (CATEGORY_MAP.containsKey(categoryName)) {
            return false; // Category already exists
        }
        CATEGORY_MAP.put(categoryName, nextCategoryId++);
        return true;
    }

    public static boolean deleteCategory(String categoryName) {
        if (CATEGORY_MAP.containsKey(categoryName)) {
            CATEGORY_MAP.remove(categoryName);
            return true;
        }
        return false;
    }
}
