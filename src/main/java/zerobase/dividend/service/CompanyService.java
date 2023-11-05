package zerobase.dividend.service;

import lombok.AllArgsConstructor;
import org.apache.commons.collections4.Trie;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.util.ObjectUtils;
import zerobase.dividend.exception.impl.NoCompanyException;
import zerobase.dividend.model.Company;
import zerobase.dividend.model.ScrapedResult;
import zerobase.dividend.persist.entity.CompanyEntity;
import zerobase.dividend.persist.CompanyRepository;
import zerobase.dividend.persist.entity.DividendEntity;
import zerobase.dividend.persist.DividendRepository;
import zerobase.dividend.scraper.Scraper;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@AllArgsConstructor
public class CompanyService {

    private final Trie<String, String> trie; // 프로그램 전체에서 1개만 적용되어야 하는 싱글톤 패턴
    private final Scraper yahooFinanceScraper;

    private final CompanyRepository companyRepository;
    private final DividendRepository dividendRepository;

    public Company save(String ticker) {
        boolean exists = this.companyRepository.existsByTicker(ticker);
        if(exists) {
            throw new RuntimeException("already exists ticker -> " + ticker);
        }
        return this.storeCompanyAndDividend(ticker);
    }

    // 회사 리스트 조회
    public Page<CompanyEntity> getAllCompany(Pageable pageable) {
        return this.companyRepository.findAll(pageable);
    }

    private Company storeCompanyAndDividend(String ticker) {
        // ticker를 기준으로 회사를 스크래핑
        Company company = this.yahooFinanceScraper.scrapCompanyByTicker(ticker);
        if(ObjectUtils.isEmpty(company)) {
            throw new RuntimeException("failed to scrap ticker -> " + ticker);
        }

        // 해당 회사가 존재할 경우, 회사의 배당금 정보를 스크래핑
        ScrapedResult scrapedResult = this.yahooFinanceScraper.scrap(company);

        // 스크래핑 결과
        CompanyEntity companyEntity = this.companyRepository.save(new CompanyEntity(company));
        List<DividendEntity> dividendEntityList = scrapedResult.getDividends().stream()
                .map(e -> new DividendEntity(companyEntity.getId(), e))// 리스트 요소를 매핑
                .collect(Collectors.toList());

        this.dividendRepository.saveAll(dividendEntityList);
        return company;
    }

    public List<String> getCompanyNameByKeyword(String keyword) {
        Pageable limit = PageRequest.of(0,10);
        Page<CompanyEntity> companyEntities = this.companyRepository.
                findByNameStartingWithIgnoreCase(keyword, limit);

        return companyEntities.stream()
                             .map(e -> e.getName())
                             .collect(Collectors.toList());
    }

    // 트라이 부분
    public void addAutocompleteKeyword(String keyword) {
        this.trie.put(keyword, null);
    }
    public List<String> autocomplete(String keyword) {
        return new ArrayList<>(this.trie.prefixMap(keyword).keySet());
    }
    public void deleteAutocompleteKeyword(String keyword) {
        this.trie.remove(keyword);
    }

    public String deleteCompany(String ticker) {
        CompanyEntity company = this.companyRepository.findByTicker(ticker)
                .orElseThrow(() -> new NoCompanyException());

        this.dividendRepository.deleteAllByCompanyId(company.getId()); // 배당금 정보 삭제
        this.companyRepository.delete(company); // 회사 삭제

        this.deleteAutocompleteKeyword(company.getName()); // 트라이 데이터 삭제
        return company.getName();
    }
}
