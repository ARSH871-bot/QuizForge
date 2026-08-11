package cs.quizzapp.prokect.backend.services;

import cs.quizzapp.prokect.backend.db.CategoryRepository;
import cs.quizzapp.prokect.backend.models.Category;
import cs.quizzapp.prokect.backend.utils.QuizCategoryMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class CategoryService {
    @Autowired
    private CategoryRepository categoryRepository;

    // Add a new category

    public Category addCategory(String name) {
        if (QuizCategoryMapper.getCategoryId(name) != null) {
            throw new IllegalArgumentException("Category already exists in predefined categories");
        }

        Optional<Category> existingCategory = categoryRepository.findByName(name);
        if (existingCategory.isPresent()) {
            throw new IllegalArgumentException("Category already exists in the database");
        }

        Category category = new Category();
        category.setName(name);
        return categoryRepository.save(category);
    }
    public List<Category> getAllCategories() {
        List<Category> categoriesFromDB = categoryRepository.findAll();

        // Add categories from CATEGORY_MAP if they are not in the database
        QuizCategoryMapper.getAllCategories().forEach((categoryName, id) -> {
            if (categoriesFromDB.stream().noneMatch(cat -> cat.getName().equals(categoryName))) {
                Category category = new Category();
                category.setName(categoryName);
                categoriesFromDB.add(category);
            }
        });

        return categoriesFromDB;
    }


    // Delete a category by name (delete from database or predefined map)
    public void deleteCategory(String name) {
        if (QuizCategoryMapper.getCategoryId(name) != null) {
            boolean deleted = QuizCategoryMapper.deleteCategory(name);
            if (!deleted) {
                throw new IllegalArgumentException("Failed to delete predefined category");
            }
            return;
        }

        Category category = categoryRepository.findByName(name)
                .orElseThrow(() -> new IllegalArgumentException("Category not found in the database"));
        categoryRepository.delete(category);
    }
}
